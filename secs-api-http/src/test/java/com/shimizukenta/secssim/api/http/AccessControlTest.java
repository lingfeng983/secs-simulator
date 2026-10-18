package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.config.ConfigException;

/**
 * 账号与角色的纯逻辑测试：解析、校验、口令比对。
 *
 * <p>不碰 HTTP——那是 {@link HttpApiServerAuthTest} 的职责。
 * </p>
 */
class AccessControlTest {

    private static final String THREE_USERS = """
            {"users": [
              {"name": "guest", "role": "viewer",   "secret": "g-pass"},
              {"name": "line1", "role": "operator", "secret": "o-pass"},
              {"name": "owner", "role": "admin",    "secret": "a-pass"}
            ]}""";

    @Test
    void singleTokenIsAdminAndSeesEverything() {
        AccessControl access = AccessControl.singleToken("t0ken");

        assertEquals(1, access.size());
        AccessControl.Principal principal = access.authenticate("t0ken");
        assertNotNull(principal);
        assertEquals("token", principal.name());
        assertEquals(ApiRole.ADMIN, principal.role());
        assertTrue(principal.role().atLeast(ApiRole.VIEWER));
        assertTrue(principal.role().atLeast(ApiRole.OPERATOR));
        assertTrue(principal.role().atLeast(ApiRole.ADMIN));
    }

    @Test
    void singleTokenRejectsBlankAndWrongSecret() {
        assertThrows(IllegalArgumentException.class, () -> AccessControl.singleToken("  "));
        assertThrows(IllegalArgumentException.class, () -> AccessControl.singleToken(null));

        AccessControl access = AccessControl.singleToken("t0ken");
        assertNull(access.authenticate("wrong"));
        assertNull(access.authenticate(""));
        assertNull(access.authenticate(null));
    }

    @Test
    void everyUserIsAuthenticatedWithItsOwnRole() {
        AccessControl access = AccessControl.parse(THREE_USERS);

        assertEquals(3, access.size());
        assertEquals(new AccessControl.Principal("guest", ApiRole.VIEWER), access.authenticate("g-pass"));
        assertEquals(new AccessControl.Principal("line1", ApiRole.OPERATOR), access.authenticate("o-pass"));
        assertEquals(new AccessControl.Principal("owner", ApiRole.ADMIN), access.authenticate("a-pass"));
        assertNull(access.authenticate("nope"));
    }

    @Test
    void roleIsCaseInsensitiveAndTrimmed() {
        AccessControl access = AccessControl.parse(
                "{\"users\":[{\"name\":\"a\",\"role\":\"  Viewer \",\"secret\":\"p\"}]}");

        assertEquals(ApiRole.VIEWER, access.authenticate("p").role());
        assertTrue(ApiRole.ADMIN.atLeast(ApiRole.OPERATOR));
        assertTrue(ApiRole.OPERATOR.atLeast(ApiRole.OPERATOR));
        assertTrue(!ApiRole.VIEWER.atLeast(ApiRole.OPERATOR));
        assertTrue(!ApiRole.VIEWER.atLeast(ApiRole.ADMIN));
    }

    @Test
    void loadReadsFileAndToleratesUtf8Bom(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("users.json");
        Files.writeString(file, "\uFEFF" + THREE_USERS, StandardCharsets.UTF_8);

        AccessControl access = AccessControl.load(file);

        assertEquals(3, access.size());
        assertNotNull(access.authenticate("a-pass"));
    }

    @Test
    void loadRejectsMissingFile(@TempDir Path tempDir) {
        assertThrows(IOException.class, () -> AccessControl.load(tempDir.resolve("nope.json")));
        assertThrows(IllegalArgumentException.class, () -> AccessControl.load(null));
    }

    @Test
    void malformedJsonIsReportedAsConfigError() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> AccessControl.parse("this is not json"));
        assertEquals("$", error.path());
    }

    @Test
    void rootMustBeAnObjectWithUsers() {
        assertEquals("$", assertThrows(ConfigException.class,
                () -> AccessControl.parse("[]")).path());
        assertEquals("$.users", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{}")).path());
        assertEquals("$.users", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{\"users\":{}}")).path());
        assertEquals("$.users", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{\"users\":[]}")).path(),
                "空账号表等于没有鉴权，必须拒绝");
    }

    @Test
    void elementMustBeAnObjectWithAllFields() {
        assertEquals("$.users[0]", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{\"users\":[\"x\"]}")).path());
        assertEquals("$.users[0].name", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{\"users\":[{\"role\":\"viewer\",\"secret\":\"p\"}]}")).path());
        assertEquals("$.users[0].role", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{\"users\":[{\"name\":\"a\",\"secret\":\"p\"}]}")).path());
        assertEquals("$.users[0].secret", assertThrows(ConfigException.class,
                () -> AccessControl.parse("{\"users\":[{\"name\":\"a\",\"role\":\"viewer\"}]}")).path());
    }

    @Test
    void unknownRoleIsRejectedWithTheOffendingPath() {
        ConfigException error = assertThrows(ConfigException.class, () -> AccessControl.parse(
                "{\"users\":[{\"name\":\"a\",\"role\":\"superuser\",\"secret\":\"p\"}]}"));

        assertEquals("$.users[0].role", error.path());
        assertTrue(error.getMessage().contains("superuser"), error.getMessage());
    }

    @Test
    void blankNameOrSecretIsRejected() {
        assertEquals("$.users[0].name", assertThrows(ConfigException.class, () -> AccessControl.parse(
                "{\"users\":[{\"name\":\"  \",\"role\":\"viewer\",\"secret\":\"p\"}]}")).path());
        assertEquals("$.users[0].secret", assertThrows(ConfigException.class, () -> AccessControl.parse(
                "{\"users\":[{\"name\":\"a\",\"role\":\"viewer\",\"secret\":\"\"}]}")).path());
    }

    @Test
    void duplicateNameOrSecretIsRejected() {
        /* 同名：鉴权结果取决于哪一条先被匹配 */
        assertEquals("$.users", assertThrows(ConfigException.class, () -> AccessControl.parse("""
                {"users": [
                  {"name": "dup", "role": "viewer",   "secret": "p1"},
                  {"name": "dup", "role": "operator", "secret": "p2"}
                ]}""")).path());

        /* 同口令配两个账号名：身份无法确定 */
        assertEquals("$.users", assertThrows(ConfigException.class, () -> AccessControl.parse("""
                {"users": [
                  {"name": "a", "role": "viewer", "secret": "same"},
                  {"name": "b", "role": "admin",  "secret": "same"}
                ]}""")).path());
    }

    @Test
    void accountListMustNotBeEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> AccessControl.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> AccessControl.of(null));
    }

    @Test
    void apiRoleRejectsUnknownText() {
        assertThrows(IllegalArgumentException.class, () -> ApiRole.fromId("root"));
        assertThrows(IllegalArgumentException.class, () -> ApiRole.fromId(""));
        assertThrows(IllegalArgumentException.class, () -> ApiRole.fromId(null));
        assertEquals(ApiRole.OPERATOR, ApiRole.fromId("OPERATOR"));
    }
}
