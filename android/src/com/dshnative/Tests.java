package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 单元测试（不依赖任何测试框架，纯手写断言）。
 *
 * 为什么要有：这个项目一直只有真机自检（SelfCheck 那 24 项偏集成，跑得慢、依赖设备状态），
 * 逻辑函数（版本比较、权限默认值、桌面索引转换、卡片判定）没有任何自动化保护。
 * 这些函数出错的方式很安静——比如"1.10 被当成比 1.9 旧"，界面上只是不提示更新。
 *
 * 这里只测纯函数：不碰网络、不碰 UI、不写用户目录。用 {@link #run(Context)} 拿结果，
 * 也可以从 dev_unit 工具调用。
 */
public final class Tests {

    public static final class Result {
        public final int total;
        public final int passed;
        public final List<String> failures;

        Result(int total, int passed, List<String> failures) {
            this.total = total;
            this.passed = passed;
            this.failures = failures;
        }

        public boolean ok() { return failures.isEmpty(); }

        public String summary() {
            return ok() ? ("全部通过（" + passed + "/" + total + "）")
                    : ("失败 " + failures.size() + " / " + total + "：" + join(failures));
        }
    }

    private Tests() {}

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("；");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    public static Result run(Context c) {
        List<String> fails = new ArrayList<>();
        int total = 0;

        // ---- 版本比较：点分数字，不能按字符串比 ----
        total++; if (!Market.isNewerThan("1.10.0", "1.9.9")) fails.add("1.10.0 应比 1.9.9 新");
        total++; if (Market.isNewerThan("2.0", "2.0")) fails.add("相同的版本不该算新");
        total++; if (!Market.isNewerThan("1.0.1", "1.0")) fails.add("1.0.1 应比 1.0 新");
        total++; if (Market.isNewerThan("1.9", "1.10")) fails.add("1.9 不该比 1.10 新");

        // ---- 权限默认值：没声明 permissions 时应放行，声明 -net 才拦 ----
        try {
            JSONObject plain = new JSONObject("{\"id\":\"a\"}");
            String[] p = Market.perms(plain);
            total++; if ("-net".equals(p[0])) fails.add("未声明权限的插件不该默认拦网");
            total++; if ("-shell".equals(p[1])) fails.add("未声明权限的插件不该默认拦命令");

            // permissions 是对象 {net:false, shell:false}，不是数组
            JSONObject noNet = new JSONObject("{\"id\":\"b\",\"permissions\":{\"net\":false}}");
            String[] p2 = Market.perms(noNet);
            total++; if (!"-net".equals(p2[0])) fails.add("net:false 应拦网");
            total++; if ("-shell".equals(p2[1])) fails.add("没声明 shell 就不该拦命令");

            JSONObject noShell = new JSONObject("{\"id\":\"c\",\"permissions\":{\"shell\":false}}");
            String[] p3 = Market.perms(noShell);
            total++; if (!"-shell".equals(p3[1])) fails.add("shell:false 应拦命令");
            total++; if ("-net".equals(p3[0])) fails.add("没声明 net 就不该拦网");

            JSONObject both = new JSONObject("{\"id\":\"d\",\"permissions\":{\"net\":false,\"shell\":false}}");
            String[] p4 = Market.perms(both);
            total++; if (!"-net".equals(p4[0]) || !"-shell".equals(p4[1])) fails.add("双禁止没生效");
        } catch (Throwable t) {
            total += 7; fails.add("权限用例异常 " + t);
        }

        // ---- 桌面版索引转换：有 name+versions 且没有 tools/capabilities 才算 ----
        try {
            JSONObject cat = new JSONObject("{\"format\":1,\"plugins\":["
                    + "{\"name\":\"lodash\",\"metadata\":{\"title\":\"Lodash\"},"
                    + "\"versions\":[{\"version\":\"4.17.21\"}]},"
                    + "{\"id\":\"native1\",\"name\":\"原生\",\"tools\":[]}]}");
            List<JSONObject> conv = Market.desktopEntries(cat);
            total++; if (conv.size() != 1) fails.add("桌面索引应只转出 1 条，实际 " + conv.size());
            if (conv.size() == 1) {
                JSONObject e = conv.get(0);
                total++; if (!"lodash".equals(e.optString("npm"))) fails.add("npm 字段错");
                total++; if (!"4.17.21".equals(e.optString("version"))) fails.add("版本没取到");
                total++; if (!Market.isDesktopEntry(e)) fails.add("没打 _desktop 标记");
            } else {
                total += 3;
            }
        } catch (Throwable t) {
            total += 4; fails.add("桌面索引用例异常 " + t);
        }

        // ---- 附件卡片判定：只有被"点名"的才挂卡 ----
        File tmp = null;
        try {
            tmp = new File(c.getCacheDir(), "unit-card.html");
            FileOutputStream os = new FileOutputStream(tmp);
            os.write("<html>x</html>".getBytes("UTF-8"));
            os.close();
            total++; if (!Attach.shouldCard(tmp, "结果在 unit-card.html 里")) fails.add("点名了却没判要挂卡");
            total++; if (Attach.shouldCard(tmp, "我做好了，没有提到文件名")) fails.add("没点名却判要挂卡");
            total++; if (Attach.shouldCard(tmp, "")) fails.add("空回答不该挂卡");
        } catch (Throwable t) {
            total += 3; fails.add("卡片用例异常 " + t);
        } finally {
            if (tmp != null) tmp.delete();
        }

        return new Result(total, total - fails.size(), fails);
    }
}
