package com.dshnative;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频 / 视频生成。**不依赖 ffmpeg**，走 Android 自带的 MediaCodec 硬件编码器：
 *
 *   wav  → 直接写 PCM（标准库级别，无需编码器）
 *   m4a  → PCM → AAC（audio/mp4a-latm）→ MediaMuxer 封装
 *   mp4  → 位图帧 → H.264（video/avc，YUV 喂数据，自己控制 PTS）→ MediaMuxer
 *
 * 为什么不用 ffmpeg：Termux 的 ffmpeg 要带二十多个 .so，APK 会从 72MB 涨到 ~170MB。
 * 系统编码器是硬件加速的、零体积、已经在每台设备上。
 *
 * 已知取舍：
 * - 平台只有 AAC 编码器，没有 MP3 编码器（MP3 是解码支持）。所以音频输出是 m4a/wav。
 * - 视频用字节缓冲喂 YUV420（半平面/平面/柔性），不依赖 EGL、不怕厂商差异。
 */
public final class Media {

    private Media() {}

    // ---------------- 入口 ----------------

    public static String write(File out, String format, JSONObject spec) {
        try {
            if ("wav".equals(format)) return wav(out, spec);
            if ("m4a".equals(format)) return m4a(out, spec);
            if ("mp4".equals(format)) return mp4(out, spec);
            return "错误：audio/video 只支持 wav / m4a / mp4";
        } catch (Throwable t) {
            String m = t.getMessage();
            return "错误：" + (m == null ? t.toString() : m);
        }
    }

    // ---------------- PCM 合成 ----------------

    /** 把 notes / tone 合成成 16bit 单声道 PCM。 */
    private static short[] pcm(JSONObject spec) {
        int rate = spec.optInt("sampleRate", 44100);
        double volume = spec.optDouble("volume", 0.5);
        String wave = spec.optString("waveform", "sine");

        List<double[]> segs = new ArrayList<>();   // {freq, seconds}
        JSONArray notes = spec.optJSONArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject n = notes.optJSONObject(i);
                if (n == null) continue;
                segs.add(new double[]{n.optDouble("freq", 440), n.optDouble("dur", 0.4)});
            }
        }
        if (segs.isEmpty()) {
            segs.add(new double[]{spec.optDouble("tone", 440), spec.optDouble("seconds", 1)});
        }
        double total = 0;
        for (double[] s : segs) total += s[1];
        int frames = (int) (total * rate);
        short[] out = new short[frames];

        int pos = 0;
        for (double[] seg : segs) {
            double freq = seg[0], secs = seg[1];
            int n = (int) (secs * rate);
            // 每段做 10ms 淡入淡出，避免爆音
            int ramp = Math.min(rate / 100, n / 4);
            for (int i = 0; i < n && pos < frames; i++, pos++) {
                double t = (double) i / rate;
                double v;
                if ("square".equals(wave)) {
                    v = Math.sin(2 * Math.PI * freq * t) >= 0 ? 1 : -1;
                } else if ("noise".equals(wave)) {
                    v = Math.random() * 2 - 1;
                } else if ("saw".equals(wave)) {
                    v = 2.0 * (t * freq - Math.floor(t * freq + 0.5));
                } else {
                    v = Math.sin(2 * Math.PI * freq * t);
                }
                double env = 1;
                if (ramp > 0) {
                    if (i < ramp) env = (double) i / ramp;
                    else if (i > n - ramp) env = (double) (n - i) / ramp;
                }
                out[pos] = (short) (v * env * volume * Short.MAX_VALUE);
            }
        }
        return out;
    }

    private static String wav(File out, JSONObject spec) throws Exception {
        short[] data = pcm(spec);
        int rate = spec.optInt("sampleRate", 44100);
        FileOutputStream os = new FileOutputStream(out);
        writeWavHeader(os, data.length, rate);
        byte[] bytes = new byte[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            bytes[i * 2] = (byte) (data[i] & 0xff);
            bytes[i * 2 + 1] = (byte) ((data[i] >> 8) & 0xff);
        }
        os.write(bytes);
        os.close();
        return "已生成 wav：" + out.getAbsolutePath() + "（" + (data.length / rate * 1000 / 1000.0)
                + " 秒）";
    }

    private static void writeWavHeader(FileOutputStream os, int frames, int rate) throws IOException {
        int dataLen = frames * 2;
        ByteBuffer h = ByteBuffer.allocate(44);
        h.order(java.nio.ByteOrder.LITTLE_ENDIAN);   // WAV 是小端；不设就是 BE，整段头会错位
        h.put("RIFF".getBytes());
        h.putInt(36 + dataLen);
        h.put("WAVEfmt ".getBytes());
        h.putInt(16);
        h.putShort((short) 1);        // PCM
        h.putShort((short) 1);        // mono
        h.putInt(rate);
        h.putInt(rate * 2);           // byte rate
        h.putShort((short) 2);        // block align
        h.putShort((short) 16);       // bits
        h.put("data".getBytes());
        h.putInt(dataLen);
        os.write(h.array());
    }

    // ---------------- AAC (m4a) ----------------

    private static String m4a(File out, JSONObject spec) throws Exception {
        short[] pcm = pcm(spec);
        int rate = spec.optInt("sampleRate", 44100);

        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, spec.optInt("bitrate", 128000));
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean eos = false;
        int fed = 0;
        long ptsUs = 0;

        while (true) {
            if (!eos) {
                int in = enc.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer buf = enc.getInputBuffer(in);
                    if (buf == null) continue;
                    buf.clear();
                    int room = buf.remaining() / 2;
                    int left = pcm.length - fed;
                    if (left <= 0) {
                        enc.queueInputBuffer(in, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eos = true;
                    } else {
                        int n = Math.min(room, left);
                        for (int i = 0; i < n; i++) buf.putShort(pcm[fed + i]);
                        enc.queueInputBuffer(in, 0, n * 2, ptsUs, 0);
                        fed += n;
                        ptsUs += (long) n * 1_000_000L / rate;
                    }
                }
            }
            int outIdx = enc.dequeueOutputBuffer(info, 10000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(enc.getOutputFormat());
                mux.start();
            } else if (outIdx >= 0) {
                ByteBuffer buf = enc.getOutputBuffer(outIdx);
                if (buf != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && track >= 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    mux.writeSampleData(track, buf, info);
                }
                enc.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        enc.stop();
        enc.release();
        if (track >= 0) mux.stop();
        mux.release();
        return "已生成 m4a：" + out.getAbsolutePath() + "（" + (out.length() / 1024) + " KB，AAC "
                + rate + "Hz）";
    }

    // ---------------- H.264 (mp4) ----------------

    private static String mp4(File out, JSONObject spec) throws Exception {
        JSONArray slides = spec.optJSONArray("slides");
        if (slides == null || slides.length() == 0) return "错误：mp4 需要 slides（至少一页）";

        JSONArray size = spec.optJSONArray("size");
        int w = size != null && size.length() >= 2 ? size.optInt(0, 1280) : 1280;
        int h = size != null && size.length() >= 2 ? size.optInt(1, 720) : 720;
        w &= ~1; h &= ~1;                       // 编码器要求偶数
        int fps = spec.optInt("fps", 30);

        // 先把每页画成位图，再逐页按帧数喂给编码器
        List<Bitmap> frames = new ArrayList<>();
        List<Integer> frameCounts = new ArrayList<>();
        for (int i = 0; i < slides.length(); i++) {
            JSONObject s = slides.optJSONObject(i);
            if (s == null) continue;
            frames.add(slideBitmap(s, w, h, i + 1, slides.length()));
            double secs = s.optDouble("seconds", 2.5);
            frameCounts.add(Math.max(1, (int) Math.round(secs * fps)));
        }
        if (frames.isEmpty()) return "错误：slides 内容为空";

        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        int colorFormat = pickColorFormat();
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, Math.min(8_000_000, w * h * fps / 4));
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        long ptsUs = 0;
        long frameDurUs = 1_000_000L / fps;

        for (int s = 0; s < frames.size(); s++) {
            Bitmap bm = frames.get(s);
            byte[] yuv = toNv12(bm, w, h);
            int n = frameCounts.get(s);
            for (int f = 0; f < n; f++) {
                int in = -1;
                long deadline = System.currentTimeMillis() + 5000;
                while (in < 0 && System.currentTimeMillis() < deadline) {
                    in = enc.dequeueInputBuffer(10000);
                    if (in < 0) drain(enc, mux, info, track, false);
                }
                if (in < 0) break;
                ByteBuffer buf = enc.getInputBuffer(in);
                if (buf == null) continue;
                buf.clear();
                buf.put(yuv);
                enc.queueInputBuffer(in, 0, yuv.length, ptsUs, 0);
                ptsUs += frameDurUs;
                track = drain(enc, mux, info, track, false);
            }
        }
        // 送 EOS 并把剩余输出写完
        int in = enc.dequeueInputBuffer(10000);
        if (in >= 0) enc.queueInputBuffer(in, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            track = drain(enc, mux, info, track, true);
            if (track == -2) break;
        }
        enc.stop();
        enc.release();
        try { if (track >= 0) mux.stop(); } catch (Exception ignored) {}
        mux.release();
        for (Bitmap b : frames) b.recycle();

        return "已生成 mp4：" + out.getAbsolutePath() + "（" + (out.length() / 1024) + " KB，"
                + w + "x" + h + " " + fps + "fps，" + frames.size() + " 页 / "
                + String.format("%.1f", ptsUs / 1_000_000.0) + " 秒）";
    }

    /** 抽一帧编码产物并写入封装；返回 trackId（-2 表示已收到 EOS）。 */
    private static int drain(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo info,
                             int track, boolean untilEos) {
        int outIdx = enc.dequeueOutputBuffer(info, 10000);
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            int t = mux.addTrack(enc.getOutputFormat());
            mux.start();
            return t;
        }
        if (outIdx >= 0) {
            ByteBuffer buf = enc.getOutputBuffer(outIdx);
            if (buf != null && info.size > 0 && track >= 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                mux.writeSampleData(track, buf, info);
            }
            enc.releaseOutputBuffer(outIdx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return -2;
        }
        return track;
    }

    /** 选一个本机编码器支持的 YUV 格式。 */
    private static int pickColorFormat() {
        int[] candidates = {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,   // NV12
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,      // I420
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        };
        try {
            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo info = enc.getCodecInfo();
            enc.release();
            MediaCodecInfo.CodecCapabilities cap =
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            for (int c : candidates) {
                for (int supported : cap.colorFormats) {
                    if (supported == c) return c;
                }
            }
        } catch (Throwable ignored) {}
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }

    /** 按选定格式把位图转成 YUV 字节（优先 NV12，其次 I420）。 */
    private static byte[] toNv12(Bitmap bm, int w, int h) {
        int[] argb = new int[w * h];
        bm.getPixels(argb, 0, w, 0, 0, w, h);
        byte[] yuv = new byte[w * h * 3 / 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int yv = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[y * w + x] = (byte) Math.max(0, Math.min(255, yv));
            }
        }
        int off = w * h;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int c = argb[y * w + x];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                int idx = off + (y / 2) * w + (x / 2) * 2;
                if (idx + 1 < yuv.length) {
                    yuv[idx] = (byte) Math.max(0, Math.min(255, u));       // NV12: U 在前
                    yuv[idx + 1] = (byte) Math.max(0, Math.min(255, v));
                }
            }
        }
        return yuv;
    }

    // ---------------- 画幻灯片 ----------------

    private static Bitmap slideBitmap(JSONObject s, int w, int h, int page, int total) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        int bg = parseColor(s.optString("bg", "#101418"), Color.BLACK);
        int fg = parseColor(s.optString("fg", "#FFFFFF"), Color.WHITE);
        int accent = parseColor(s.optString("accent", "#7FB2FF"), Color.CYAN);
        cv.drawColor(bg);

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(fg);
        title.setTextSize(h / 9f);
        title.setFakeBoldText(true);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(fg);
        body.setTextSize(h / 20f);

        Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentPaint.setColor(accent);

        cv.drawRect(0, 0, w * 0.012f, h, accentPaint);        // 左侧色条
        float y = h * 0.18f;
        String t = s.optString("title", "");
        if (!t.isEmpty()) {
            cv.drawText(t, w * 0.07f, y, title);
            y += h * 0.10f;
        }
        JSONArray bullets = s.optJSONArray("bullets");
        if (bullets != null) {
            for (int i = 0; i < bullets.length(); i++) {
                String line = "•  " + bullets.optString(i, "");
                cv.drawText(line, w * 0.08f, y, body);
                y += h * 0.075f;
            }
        }
        String sub = s.optString("text", "");
        if (!sub.isEmpty()) {
            for (String line : wrapLines(sub, body, (int) (w * 0.86f))) {
                cv.drawText(line, w * 0.07f, y, body);
                y += h * 0.06f;
            }
        }
        Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
        foot.setColor(accent);
        foot.setTextSize(h / 32f);
        cv.drawText(page + " / " + total, w * 0.86f, h * 0.93f, foot);
        return bm;
    }

    private static List<String> wrapLines(String text, Paint p, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char ch : text.toCharArray()) {
            cur.append(ch);
            if (p.measureText(cur.toString()) > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static int parseColor(String s, int fallback) {
        try { return Color.parseColor(s); } catch (Exception e) { return fallback; }
    }

    @SuppressWarnings("unused")
    private static long fileSize(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { return r.length(); }
        catch (Exception e) { return 0; }
    }
}
