package com.aicap.security;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住"JWT 密钥必须显式提供,且不得再用历史默认值"(2026-10-15)。
 *
 * <p>这些断言的价值不在覆盖代码,而在<b>把一条约定变成不可回退的约束</b>:
 * 本仓曾是公开默认密钥 + 一句"生产必须覆盖"的注释,而注释拦不住任何人 ——
 * 漏配的部署照常起来、照常发 token,只是任何人都能伪造 admin 身份。
 * 与 {@code deploy/test_verify.py} 里"把 compose 的 {@code :?} 改回默认值就必须失败"
 * 是同一类测试,只是一个管编排、一个管应用。
 *
 * <p>刻意<b>不</b>用 {@code @SpringBootTest}:那会让它依赖测试夹具密钥的存在,
 * 而这里要验的恰恰是"没有密钥时的行为"。
 */
class JwtSecretFailClosedTest {

    /** 与 application.yml 里退役的那把一致;改回来时必须让这里的断言红掉。 */
    private static final String RETIRED_DEV_DEFAULT = "aicap-dev-secret-change-me-please-use-env-0123456789";

    private static final long EXPIRE_MINUTES = 720;

    @Test
    void blankSecretFailsFastWithActionableMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil("", EXPIRE_MINUTES));
        assertTrue(e.getMessage().contains("JWT_SECRET"), "报错必须点出该配哪个键:" + e.getMessage());
        assertThrows(IllegalStateException.class, () -> new JwtUtil(null, EXPIRE_MINUTES));
        assertThrows(IllegalStateException.class, () -> new JwtUtil("   ", EXPIRE_MINUTES));
    }

    @Test
    void retiredDefaultSecretIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil(RETIRED_DEV_DEFAULT, EXPIRE_MINUTES));
        assertTrue(e.getMessage().contains("历史默认值"), "报错必须说明它是历史默认值:" + e.getMessage());
    }

    @Test
    void secretShorterThan256BitIsRejected() {
        String thirtyOneBytes = "0123456789012345678901234567890";
        assertEquals(31, thirtyOneBytes.length());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil(thirtyOneBytes, EXPIRE_MINUTES));
        assertTrue(e.getMessage().contains("32"), "报错必须说明最低长度:" + e.getMessage());
    }

    @Test
    void thirtyTwoByteSecretRoundTrips() {
        String thirtyTwoBytes = "01234567890123456789012345678901";
        JwtUtil jwt = new JwtUtil(thirtyTwoBytes, EXPIRE_MINUTES);

        String token = jwt.createToken(7);
        assertNotNull(token);
        assertEquals(7, jwt.parseUserId(token));
        // 签名被改动的 token 必须解析失败,而不是"能解出内容就算数"
        assertNull(jwt.parseUserId(token + "x"));
    }

    /** 报错里只允许出现指纹,不允许出现密钥本身(异常文本会进日志与响应)。 */
    @Test
    void failureMessagesNeverEchoTheSecret() {
        String secret = "distinctive-short-secret";   // 24 字节:短到必然被拒
        assertTrue(secret.length() < 32);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil(secret, EXPIRE_MINUTES));
        assertFalse(e.getMessage().contains(secret), "报错回显了密钥本身:" + e.getMessage());
        assertFalse(e.getMessage().contains("distinctive"), "报错回显了密钥片段:" + e.getMessage());
    }

    /**
     * 结构约束:application.yml 里 {@code aicap.jwt.secret} 的默认值必须是空的。
     *
     * <p>读的是 classpath 上真实的 application.yml(测试资源里没有同名文件,
     * 所以解析到的就是打包进产物的那份)。
     */
    @Test
    void applicationYmlKeepsNoUsableJwtDefault() throws Exception {
        String yml = readClasspathText("application.yml");
        assertFalse(yml.contains(RETIRED_DEV_DEFAULT),
                "application.yml 不得再出现历史默认密钥");

        String secretLine = null;
        for (String line : yml.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("secret:")) {
                secretLine = trimmed;
                break;
            }
        }
        assertNotNull(secretLine, "application.yml 里找不到 aicap.jwt.secret");
        assertEquals("secret: ${JWT_SECRET:}", secretLine,
                "JWT 默认值必须留空,否则漏配的部署会静默用公开密钥:");
    }

    /** 只找第一个 secret: 行——它必须就是 aicap.jwt 下的那一条。 */
    @Test
    void theSingleSecretLineIsTheJwtOne() throws Exception {
        List<String> hits = new ArrayList<>();
        for (String line : readClasspathText("application.yml").split("\r?\n")) {
            if (line.trim().startsWith("secret:")) {
                hits.add(line.trim());
            }
        }
        assertEquals(1, hits.size(), "secret: 行不止一处,断言会指向错误的键:" + hits);
    }

    private static String readClasspathText(String name) throws Exception {
        ClassLoader loader = JwtSecretFailClosedTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(name)) {
            assertNotNull(in, name + " 不在 classpath 上");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
