import com.dshs.Policy;

/**
 * 策略层与审计链的纯 JVM 测试 —— 不依赖模拟器，javac 直接跑。
 *
 * 这是在验证整个 App 的安全内核：档位缺失时必须 DENY，破坏性动作必须 CONFIRM，
 * 审计链必须能查出被改动过的记录。
 *
 * 跑法（在工作区根）：
 *   javac -encoding UTF-8 -d build/test -sourcepath android/src tools/test/PolicyTest.java
 *   java -cp build/test PolicyTest
 */

public final class PolicyTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        // ---- 档位：APP 恒可用 ----
        expect("fs.read 无任何档 → AUTO", Policy.Decision.AUTO, d("fs.read", false, false, false));
        expect("fs.write 无任何档 → CONFIRM", Policy.Decision.CONFIRM, d("fs.write", false, false, false));
        expect("app.list 无任何档 → AUTO", Policy.Decision.AUTO, d("app.list", false, false, false));

        // ---- 档位：A11Y 关闭时，界面能力必须 DENY（失败即关闭）----
        expect("ui.read 无障碍关 → DENY", Policy.Decision.DENY, d("ui.read", false, false, false));
        expect("ui.act 无障碍关 → DENY", Policy.Decision.DENY, d("ui.act", false, false, false));
        // ---- 打开后按策略工作 ----
        expect("ui.read 无障碍开 → AUTO", Policy.Decision.AUTO, d("ui.read", false, true, false));
        expect("ui.act 无障碍开 → CONFIRM", Policy.Decision.CONFIRM, d("ui.act", false, true, false));

        // ---- 档位：ROOT ----
        expect("shell 无 root → CONFIRM（app 身份可跑，每次确认）", Policy.Decision.CONFIRM, d("shell", false, true, false));
        expect("shell 有 root → CONFIRM", Policy.Decision.CONFIRM, d("shell", true, false, false));

        // ---- 档位：ADB（P2 前恒不可用）----
        expect("sys.settings 无 ADB → DENY", Policy.Decision.DENY, d("sys.settings", false, true, false));
        expect("pkg.install 无 ADB → DENY", Policy.Decision.DENY, d("pkg.install", false, false, false));
        expect("pkg.install 有 ADB → CONFIRM", Policy.Decision.CONFIRM, d("pkg.install", false, false, true));

        // ---- 未知能力一律 DENY（白名单式，不许隐式放行）----
        expect("未知能力 → DENY", Policy.Decision.DENY, d("rm.rf", true, true, true));

        // ---- 能力摘要不包含不可用档 ----
        String s1 = Policy.summary(false, false, false);
        check("summary 无 a11y 时不含 ui.read", !s1.contains("ui.read"));
        String s2 = Policy.summary(false, true, false);
        check("summary 有 a11y 时含 ui.read", s2.contains("ui.read"));

        // ---- 审计链 ----
        java.io.File dir = new java.io.File("build/test-audit-" + System.nanoTime());
        if (!dir.mkdirs()) throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
        Policy.Audit.append(dir, "fs.read", "auto", "/sdcard/a.txt");
        Policy.Audit.append(dir, "ui.act", "human", "APPROVE tap text=发送");
        Policy.Audit.append(dir, "shell", "human", "REJECT rm -rf /");

        int n = Policy.Audit.count(dir);
        expectInt("审计条目数", 3, n);

        java.util.List<String> lines = java.nio.file.Files.readAllLines(
                new java.io.File(dir, "audit.log").toPath(), java.nio.charset.StandardCharsets.UTF_8);
        String prev = "GENESIS";
        boolean chainOk = true;
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab <= 0) { chainOk = false; break; }
            String hash = line.substring(0, tab);
            String body = line.substring(tab + 1);
            String want = sha256(prev + body);
            if (!hash.equals(want)) { chainOk = false; break; }
            prev = hash;
        }
        check("审计链逐条可验证", chainOk);

        // 篡改一条 → 链条必须断
        java.util.List<String> bad = new java.util.ArrayList<>(lines);
        bad.set(1, bad.get(1).replace("APPROVE", "APPROVE-x"));
        java.nio.file.Files.write(new java.io.File(dir, "audit.log").toPath(), bad,
                java.nio.charset.StandardCharsets.UTF_8);
        String p2 = "GENESIS";
        boolean detect = false;
        for (String line : bad) {
            int tab = line.indexOf('\t');
            String hash = line.substring(0, tab);
            String body = line.substring(tab + 1);
            if (!hash.equals(sha256(p2 + body))) { detect = true; break; }
            p2 = hash;
        }
        check("篡改后链条断裂可被发现", detect);

        System.out.println();
        System.out.println("PASS " + pass + "   FAIL " + fail);
        if (fail > 0) System.exit(1);
    }

    private static Policy.Decision d(String cap, boolean root, boolean a11y, boolean adb) {
        return Policy.decide(cap, root, a11y, adb);
    }

    private static void expect(String what, Policy.Decision want, Policy.Decision got) {
        boolean ok = want == got;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "  (got " + got + ")");
        if (ok) pass++; else fail++;
    }

    private static void expectInt(String what, int want, int got) {
        boolean ok = want == got;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "  (want " + want + ", got " + got + ")");
        if (ok) pass++; else fail++;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    private static String sha256(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] dg = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : dg) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
