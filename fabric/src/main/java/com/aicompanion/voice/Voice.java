package com.aicompanion.voice;

import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;
import com.aicompanion.util.Json;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 耳朵和嘴。
 *
 *   STT —— Vosk 中文离线识别（反射调用，运行时没装就是静默禁用，不崩游戏）
 *   TTS —— Kokoro（本地 8880）或 Edge TTS 服务，拿回音频字节后本地播放
 *
 * 两条链路都是纯 JDK + HTTP，线程全在 Async 池里，主线程不碰。
 */
public final class Voice {

    private Voice() {}

    // ================================================================ STT

    private static Object voskModel;
    private static Constructor<?> recognizerCtor;
    private static Method acceptWaveForm;
    private static Method getResult;
    private static Method getFinalResult;
    private static volatile boolean voskTried;
    private static final AtomicBoolean listening = new AtomicBoolean(false);
    private static Thread micThread;

    /** 尝试加载 Vosk（反射）。返回 true 表示可识别。 */
    public static synchronized boolean ensureVosk() {
        if (voskTried) {
            return voskModel != null;
        }
        voskTried = true;
        AiConfig.Stt c = AiConfig.INSTANCE.stt;
        try {
            Path modelPath = Path.of(c.voskModelPath);
            if (!Files.isDirectory(modelPath)) {
                System.err.println("[aicompanion] Vosk 模型目录不存在: " + c.voskModelPath);
                return false;
            }
            Class<?> modelCls = Class.forName("org.vosk.Model");
            Class<?> recCls = Class.forName("org.vosk.Recognizer");
            Constructor<?> mc = modelCls.getConstructor(String.class);
            voskModel = mc.newInstance(modelPath.toString());
            recognizerCtor = recCls.getConstructor(modelCls, float.class);
            Class<?> libCls = Class.forName("org.vosk.LibVosk");
            acceptWaveForm = recCls.getMethod("acceptWaveForm", byte[].class, int.class);
            getResult = recCls.getMethod("getResult");
            getFinalResult = recCls.getMethod("getFinalResult");
            // 静音日志噪音
            try {
                libCls.getMethod("setLogLevel", int.class).invoke(null, -1);
            } catch (ReflectiveOperationException ignored) {
                // 老版本没有这个方法
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[aicompanion] Vosk 不可用（把 vosk 的 jar 丢进 mods 或关掉语音输入）: " + e);
            return false;
        }
    }

    public static boolean isListening() {
        return listening.get();
    }

    /**
     * 开始听，识别出一句完整的话就回调一次（回调在后台线程！回游戏请自己 Async.main）。
     * 再调一次会先停下上一轮。
     */
    public static void startListening(Consumer<String> onText) {
        if (!ensureVosk()) {
            onText.accept("");
            return;
        }
        AiConfig.Stt cfg = AiConfig.INSTANCE.stt;
        stopListening();
        listening.set(true);
        int rate = cfg.sampleRate <= 0 ? 16000 : cfg.sampleRate;
        micThread = new Thread(() -> {
            TargetDataLine line = null;
            try {
                AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("[aicompanion] 找不到可用麦克风");
                    return;
                }
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(fmt, rate * 2);
                line.start();
                Object recognizer = recognizerCtor.newInstance(voskModel, (float) rate);
                byte[] buf = new byte[4096];
                StringBuilder heard = new StringBuilder();
                long lastVoice = System.currentTimeMillis();
                while (listening.get()) {
                    int read = line.read(buf, 0, buf.length);
                    if (read <= 0) {
                        continue;
                    }
                    Object accepted = acceptWaveForm.invoke(recognizer, buf, read);
                    if (Boolean.TRUE.equals(accepted)) {
                        lastVoice = System.currentTimeMillis();
                    }
                    String partial = (String) getResult.invoke(recognizer);
                    String part = Json.str(partial, "partial");
                    if (part != null && !part.isBlank()) {
                        heard.setLength(0);
                        heard.append(part);
                        lastVoice = System.currentTimeMillis();
                    }
                    // 说完 1.2 秒没动静就结算这一句
                    if (heard.length() > 0 && System.currentTimeMillis() - lastVoice > 1200) {
                        String finalJson = (String) getFinalResult.invoke(recognizer);
                        String text = Json.str(finalJson, "text");
                        if (text == null || text.isBlank()) {
                            text = heard.toString();
                        }
                        heard.setLength(0);
                        text = text.replace(" ", "").trim();
                        if (!text.isEmpty()) {
                            onText.accept(text);
                            listening.set(false); // 一句话一条命令
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[aicompanion] 录音出错: " + e);
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }
                listening.set(false);
            }
        }, "AICompanion-Mic");
        micThread.setDaemon(true);
        micThread.start();
    }

    public static void stopListening() {
        listening.set(false);
        Thread t = micThread;
        micThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    // ================================================================ TTS

    /** 异步说一句：合成 + 播放都在后台。 */
    public static CompletableFuture<Void> speakAsync(String text) {
        AiConfig.Tts cfg = AiConfig.INSTANCE.tts;
        if (!cfg.enabled || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String line = text.length() > Math.max(20, cfg.maxChars) ? text.substring(0, Math.max(20, cfg.maxChars)) : text;
        return Async.supply(() -> synthesize(line)).thenAccept(bytes -> {
            if (bytes != null && bytes.length > 0) {
                play(bytes);
            }
        });
    }

    /** 按 provider 合成，返回音频字节（优先 WAV）。 */
    private static byte[] synthesize(String text) {
        AiConfig.Tts cfg = AiConfig.INSTANCE.tts;
        String provider = cfg.provider == null ? "edge" : cfg.provider.trim().toLowerCase();
        try {
            if (provider.contains("kokoro")) {
                String body = Json.obj(
                        Json.pair("model", Json.quote(cfg.kokoroModel)),
                        Json.pair("input", Json.quote(text)),
                        Json.pair("voice", Json.quote(cfg.kokoroVoice)),
                        Json.pair("response_format", Json.quote("wav")));
                byte[] wav = Async.postBytes(cfg.kokoroUrl, body, null, 60).get();
                if (wav != null && wav.length > 0) {
                    return wav;
                }
            }
            // edge 服务（本机中转，自己控制返回格式，尽量让它回 wav）
            String body = Json.obj(
                    Json.pair("text", Json.quote(text)),
                    Json.pair("voice", Json.quote(cfg.edgeVoice)),
                    Json.pair("format", Json.quote("wav")));
            return Async.postBytes(cfg.edgeUrl, body, null, 60).get();
        } catch (Exception e) {
            System.err.println("[aicompanion] 语音合成失败: " + e);
            return null;
        }
    }

    /** 播放音频字节：能当 WAV 就直接播，否则落盘交给系统播放器。 */
    public static void play(byte[] audio) {
        if (audio == null || audio.length == 0) {
            return;
        }
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio));
            AudioFormat fmt = ais.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                throw new IOException("播放设备不支持 " + fmt);
            }
            try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                line.open(fmt);
                line.start();
                float vol = (float) Math.max(0.0, Math.min(1.0, AiConfig.INSTANCE.tts.volume));
                byte[] buf = new byte[8192];
                int n;
                while ((n = ais.read(buf)) > 0) {
                    if (vol < 0.999f && fmt.getSampleSizeInBits() == 16) {
                        applyVolume16(buf, n, vol);
                    }
                    line.write(buf, 0, n);
                }
                line.drain();
            }
            ais.close();
        } catch (Exception e) {
            playViaSystem(audio);
        }
    }

    private static void applyVolume16(byte[] buf, int len, float vol) {
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            s = (short) (s * vol);
            buf[i] = (byte) (s & 0xFF);
            buf[i + 1] = (byte) ((s >> 8) & 0xFF);
        }
    }

    /** mp3/ogg 兜底：写临时文件，ffplay → PowerShell 依次尝试。 */
    private static void playViaSystem(byte[] audio) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aicompanion-voice");
            Files.createDirectories(dir);
            String ext = isMp3(audio) ? ".mp3" : ".ogg";
            Path file = dir.resolve("tts-" + System.currentTimeMillis() + ext);
            Files.write(file, audio);
            String p = file.toAbsolutePath().toString();
            if (runQuietly("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", p)) {
                return;
            }
            runQuietly("powershell", "-NoProfile", "-Command",
                    "Add-Type -AssemblyName presentationCore;"
                            + "$m=New-Object System.Windows.Media.MediaPlayer;"
                            + "$m.Open([uri]'" + p + "');$m.Play();Start-Sleep -Milliseconds 4000");
        } catch (Exception e) {
            System.err.println("[aicompanion] 播放失败: " + e);
        }
    }

    private static boolean isMp3(byte[] b) {
        return b.length > 3 && ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || (b[0] & 0xFF) == 0xFF);
    }

    private static boolean runQuietly(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 列出可用的系统声音设备（设置界面用）。 */
    public static List<String> outputDevices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
            out.add(mi.getName());
        }
        return out;
    }

    /** 供外部检查音频服务是否在线。 */
    public static boolean ttsServiceAlive() {
        AiConfig.Tts cfg = AiConfig.INSTANCE.tts;
        String url = cfg.provider != null && cfg.provider.toLowerCase().contains("kokoro") ? cfg.kokoroUrl : cfg.edgeUrl;
        int idx = url.indexOf("/", url.indexOf("//") + 2);
        String base = idx > 0 ? url.substring(0, idx) : url;
        return Async.ping(base, 3);
    }

    /** 测试用：写一段空 WAV 头方便排查。 */
    public static File writeDebugWav(byte[] audio, String name) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aicompanion-voice");
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.write(f, audio);
        return f.toFile();
    }

    @SuppressWarnings("unused")
    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
