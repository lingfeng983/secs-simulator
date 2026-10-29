package com.shimizukenta.secssim.core.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import com.shimizukenta.secssim.core.curve.Rng;

/**
 * 模板图：把多个模板按依赖关系排成一条求值序列。
 *
 * <p>它承担三件事，都是运行期靠人眼发现不了的：</p>
 * <ol>
 *   <li><b>拓扑排序</b>：被依赖的实例先算；</li>
 *   <li><b>环检测</b>：A 依赖 B、B 又依赖 A 时直接拒绝，而不是在运行期无限递归；</li>
 *   <li><b>单写者</b>：一个 SVID 只能被一个输出绑定，否则"谁最后写谁生效"，
 *       数据会随机漂移且无法复现。</li>
 * </ol>
 */
public final class TemplateGraph {

    /**
     * 一个模板实例。
     *
     * @param id            实例标识（DAG 内唯一）
     * @param template      模板
     * @param svidByOutput  输出名 → 绝对 SVID
     * @param params        该实例的参数覆盖链
     */
    public record Instance(String id, Template template, Map<String, Integer> svidByOutput, ParameterLayers params) {

        /**
         * 校验并创建。
         *
         * @param id            实例标识
         * @param template      模板
         * @param svidByOutput  输出绑定
         * @param params        参数
         */
        public Instance {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("instance id must not be blank");
            }
            if (template == null) {
                throw new IllegalArgumentException("template must not be null: " + id);
            }
            svidByOutput = Map.copyOf(svidByOutput == null ? Map.of() : svidByOutput);
            params = params == null ? ParameterLayers.empty() : params;

            for (String output : svidByOutput.keySet()) {
                if (!template.outputs().contains(output)) {
                    throw new GraphException("UNKNOWN_OUTPUT",
                            "实例 " + id + " 绑定了模板不存在的输出: " + output
                                    + "（可用: " + template.outputs() + "）");
                }
            }
        }
    }

    private final List<Instance> ordered;
    private final Map<String, Instance> byId;

    private TemplateGraph(List<Instance> ordered) {
        this.ordered = List.copyOf(ordered);
        this.byId = new LinkedHashMap<>();
        for (Instance instance : ordered) {
            this.byId.put(instance.id(), instance);
        }
    }

    /**
     * 新建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 求值顺序（拓扑序）。
     *
     * @return 实例列表
     */
    public List<Instance> ordered() {
        return this.ordered;
    }

    /**
     * 实例数量。
     *
     * @return 数量
     */
    public int size() {
        return this.ordered.size();
    }

    /**
     * 按某个实例标识取实例。
     *
     * @param id 标识
     * @return 实例
     */
    public Instance instance(String id) {
        Instance instance = this.byId.get(id);
        if (instance == null) {
            throw new GraphException("UNKNOWN_INSTANCE", "未知实例: " + id);
        }
        return instance;
    }

    /**
     * 求某一时刻全部绑定的 SVID 值。
     *
     * @param tMs 时刻
     * @param rng 根噪声源（每个实例会自行派生）
     * @return SVID → 值
     */
    public Map<Integer, Double> evaluate(long tMs, Rng rng) {
        Map<String, Double> produced = new HashMap<>();
        Map<Integer, Double> svids = new LinkedHashMap<>();

        for (Instance instance : this.ordered) {
            EvalContext context = new GraphContext(instance, produced, rng.derive("instance:" + instance.id()), tMs);
            instance.template().evaluate(tMs, context, (output, value) ->
                    produced.put(instance.id() + "." + output, value));

            for (Map.Entry<String, Integer> binding : instance.svidByOutput().entrySet()) {
                String key = instance.id() + "." + binding.getKey();
                Double value = produced.get(key);
                if (value == null) {
                    throw new GraphException("MISSING_OUTPUT",
                            "实例 " + instance.id() + " 没有产生输出: " + binding.getKey());
                }
                svids.put(binding.getValue(), value);
            }
        }

        return Map.copyOf(svids);
    }

    /**
     * 在时间区间上逐点求值（预览批算的基础）。
     *
     * <p><b>懒迭代</b>：返回 {@link Iterable} 而非物化的列表，调用方按需取用、可随时中止，
     * 不会因为窗口极大（细步长 + 长时间跨度）而一次性耗尽内存。步长固定，时刻严格递增。
     * </p>
     *
     * @param fromMs 起始时刻
     * @param toMs   结束时刻（含）
     * @param stepMs 步长（必须 ≥ 1）
     * @param rng    根噪声源
     * @return 每个时刻一份 SVID → 值（升序，懒生成）
     */
    public Iterable<Map<Integer, Double>> evaluateRange(long fromMs, long toMs, long stepMs, Rng rng) {
        if (stepMs < 1L) {
            throw new IllegalArgumentException("stepMs must be >= 1");
        }

        return () -> new Iterator<Map<Integer, Double>>() {
            private long t = fromMs;

            @Override
            public boolean hasNext() {
                return this.t <= toMs;
            }

            @Override
            public Map<Integer, Double> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                long at = this.t;
                this.t += stepMs;
                return evaluate(at, rng);
            }
        };
    }

    /** 求值上下文实现。 */
    private static final class GraphContext implements EvalContext {

        private final Instance instance;
        private final Map<String, Double> produced;
        private final Rng rng;
        private final long tMs;

        GraphContext(Instance instance, Map<String, Double> produced, Rng rng, long tMs) {
            this.instance = instance;
            this.produced = produced;
            this.rng = rng;
            this.tMs = tMs;
        }

        @Override
        public long timeMs() {
            return this.tMs;
        }

        @Override
        public double param(String name) {
            return this.instance.params().resolve(name).orElseThrow(() -> new GraphException("UNKNOWN_PARAM",
                    "实例 " + this.instance.id() + " 引用了未定义参数: " + name));
        }

        @Override
        public double input(String reference) {
            Double value = this.produced.get(reference);
            if (value == null) {
                throw new GraphException("UNKNOWN_INPUT",
                        "实例 " + this.instance.id() + " 引用了不存在的输入: " + reference);
            }
            return value;
        }

        @Override
        public Rng rng() {
            return this.rng;
        }
    }

    /**
     * 图构建器。
     */
    public static final class Builder {

        private final List<Instance> instances = new ArrayList<>();

        private Builder() {
        }

        /**
         * 添加实例。
         *
         * @param instance 实例
         * @return 构建器
         */
        public Builder add(Instance instance) {
            this.instances.add(instance);
            return this;
        }

        /**
         * 构建并校验。
         *
         * @return 图
         * @throws GraphException 重复标识 / 重复 SVID / 未知引用 / 环
         */
        public TemplateGraph build() {
            this.checkDuplicates();
            this.checkSvidOwnership();

            Map<String, Instance> byId = new LinkedHashMap<>();
            for (Instance instance : this.instances) {
                byId.put(instance.id(), instance);
            }

            Map<String, Set<String>> dependencies = this.collectDependencies(byId);
            List<Instance> sorted = this.topologicalSort(byId, dependencies);
            return new TemplateGraph(sorted);
        }

        private void checkDuplicates() {
            Set<String> seen = new HashSet<>();
            for (Instance instance : this.instances) {
                if (!seen.add(instance.id())) {
                    throw new GraphException("DUPLICATE_INSTANCE", "实例标识重复: " + instance.id());
                }
            }
        }

        private void checkSvidOwnership() {
            Map<Integer, String> owner = new HashMap<>();
            for (Instance instance : this.instances) {
                for (Map.Entry<String, Integer> binding : instance.svidByOutput().entrySet()) {
                    String previous = owner.putIfAbsent(binding.getValue(), instance.id());
                    if (previous != null) {
                        throw new GraphException("DUPLICATE_SVID",
                                "SVID " + binding.getValue() + " 被多个输出绑定: " + previous
                                        + " 与 " + instance.id());
                    }
                }
            }
        }

        private Map<String, Set<String>> collectDependencies(Map<String, Instance> byId) {
            Map<String, Set<String>> dependencies = new LinkedHashMap<>();

            for (Instance instance : this.instances) {
                Set<String> deps = new LinkedHashSet<>();

                for (String ref : instance.template().inputRefs()) {
                    /* 用最后一个点分隔：实例名本身允许带点（如 cell.1），
                       取第一个点会把实例名截断成另一个存在的实例，静默绑错。 */
                    int dot = ref.lastIndexOf('.');
                    if (dot <= 0 || dot == ref.length() - 1) {
                        throw new GraphException("BAD_REFERENCE",
                                "实例 " + instance.id() + " 的输入引用格式非法: " + ref
                                        + "（应写成 实例.输出）");
                    }

                    String targetId = ref.substring(0, dot);
                    String targetOutput = ref.substring(dot + 1);

                    Instance target = byId.get(targetId);
                    if (target == null) {
                        throw new GraphException("UNKNOWN_INSTANCE",
                                "实例 " + instance.id() + " 依赖了不存在的实例: " + targetId);
                    }
                    if (!target.template().outputs().contains(targetOutput)) {
                        throw new GraphException("UNKNOWN_OUTPUT",
                                "实例 " + instance.id() + " 依赖了 " + targetId
                                        + " 不存在的输出: " + targetOutput);
                    }
                    deps.add(targetId);
                }

                dependencies.put(instance.id(), deps);
            }

            return dependencies;
        }

        private List<Instance> topologicalSort(Map<String, Instance> byId, Map<String, Set<String>> dependencies) {
            Map<String, Integer> indegree = new LinkedHashMap<>();
            for (String id : byId.keySet()) {
                indegree.put(id, 0);
            }
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                indegree.put(entry.getKey(), entry.getValue().size());
            }

            LinkedHashSet<String> ready = new LinkedHashSet<>();
            for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
                if (entry.getValue() == 0) {
                    ready.add(entry.getKey());
                }
            }

            List<Instance> sorted = new ArrayList<>();
            while (!ready.isEmpty()) {
                String id = ready.iterator().next();
                ready.remove(id);
                sorted.add(byId.get(id));

                for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                    if (entry.getValue().contains(id)) {
                        int remaining = indegree.merge(entry.getKey(), -1, Integer::sum);
                        if (remaining == 0) {
                            ready.add(entry.getKey());
                        }
                    }
                }
            }

            if (sorted.size() != byId.size()) {
                List<String> cyclic = byId.keySet().stream()
                        .filter(id -> sorted.stream().noneMatch(i -> i.id().equals(id)))
                        .toList();
                throw new GraphException("CYCLE", "模板图存在环，涉及实例: " + cyclic);
            }

            return sorted;
        }
    }
}
