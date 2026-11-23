package com.dshnative;

/** 扩展名 → MIME。够覆盖这个应用会产出的文件类型即可。 */
final class Mimes {

    private Mimes() {}

    static String of(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".docx"))
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx"))
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pptx"))
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".sh") || n.endsWith(".py") || n.endsWith(".java") || n.endsWith(".js"))
            return "text/plain";
        return "application/octet-stream";
    }
}
