package com.liskovsoft.youtubeapi.videoinfo.bench;

import android.app.Application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathTypeAdapter;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoUrlHolder;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.VideoFormat;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEWTUBE(open-cpu): offline micro-benchmark of the CPU a video open spends inside
 * MediaServiceCore after the /player response arrives: brotli decode, response text, JSON tree,
 * JsonPath mapping, the URL transform, YouTubeMediaItemFormatInfo.from and the generated MPD.
 * Opt-in only (skipped unless NEWTUBE_BENCH_DIR names a directory of captured /player JSON
 * fixtures, optionally with a sibling .br file each). Besides timing, it writes a canonical dump
 * of every phase's output per fixture to $NEWTUBE_BENCH_DIR/out/NAME.LABEL.txt, so two builds can
 * be diffed for byte-identical results.
 *
 * <pre>NEWTUBE_BENCH_DIR=... NEWTUBE_BENCH_LABEL=before gw :youtubeapi:testDebugUnitTest \
 *     --tests '*PlayerOpenCpuBenchTest*' --rerun</pre>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerOpenCpuBenchTest {
    private static final String[] PHASES = {"brotli", "text", "tree", "map", "parse", "result",
            "transform", "from", "mpd", "gzip"};

    private static final class Fixture {
        String name;
        byte[] json;
        byte[] br;
        byte[] gzip; // the same body gzip-compressed (level 6), for the Accept-Encoding comparison
        AppClient client;
        long[][] samples; // [phase][iteration]
        long[] first = new long[PHASES.length];
    }

    @Test
    public void benchmark() throws Exception {
        String dirName = System.getenv("NEWTUBE_BENCH_DIR");
        Assume.assumeTrue("set NEWTUBE_BENCH_DIR to run", dirName != null);
        String label = System.getenv("NEWTUBE_BENCH_LABEL");
        label = label != null ? label : "run";
        int warmup = Integer.parseInt(envOr("NEWTUBE_BENCH_WARMUP", "200"));
        int iterations = Integer.parseInt(envOr("NEWTUBE_BENCH_ITER", "200"));

        File dir = new File(dirName);
        File outDir = new File(dir, "out");
        outDir.mkdirs();
        List<Fixture> fixtures = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        Arrays.sort(files);
        for (File file : files) {
            Fixture f = new Fixture();
            f.name = file.getName().replace(".json", "");
            f.json = Files.readAllBytes(file.toPath());
            File br = new File(dir, file.getName() + ".br");
            f.br = br.exists() ? Files.readAllBytes(br.toPath()) : null;
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(gz) {
                { def.setLevel(6); }
            }) {
                out.write(f.json);
            }
            f.gzip = gz.toByteArray();
            f.client = f.name.startsWith("tvhtml5") ? AppClient.TV_TIZEN : AppClient.VISIONOS;
            f.samples = new long[PHASES.length][iterations];
            fixtures.add(f);
        }

        ParseContext parser = com.jayway.jsonpath.JsonPath.using(Configuration.builder()
                .mappingProvider(new GsonMappingProvider()).jsonProvider(new GsonJsonProvider()).build());

        // First (cold) iteration per fixture, with the canonical dump.
        for (Fixture f : fixtures) {
            StringBuilder dump = new StringBuilder();
            runOnce(f, parser, f.first, dump);
            Files.write(new File(outDir, f.name + "." + label + ".txt").toPath(),
                    dump.toString().getBytes(StandardCharsets.UTF_8));
        }
        long[] scratch = new long[PHASES.length];
        for (int i = 0; i < warmup; i++) {
            for (Fixture f : fixtures) {
                runOnce(f, parser, scratch, null);
            }
        }
        for (int i = 0; i < iterations; i++) {
            for (Fixture f : fixtures) {
                runOnce(f, parser, scratch, null);
                for (int p = 0; p < PHASES.length; p++) {
                    f.samples[p][i] = scratch[p];
                }
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("label=").append(label).append(" warmup=").append(warmup)
                .append(" iterations=").append(iterations).append('\n');
        report.append(String.format("%-28s", "fixture"));
        for (String phase : PHASES) {
            report.append(String.format("%16s", phase));
        }
        report.append('\n');
        for (Fixture f : fixtures) {
            report.append(String.format("%-28s", f.name + " br=" + (f.br != null ? f.br.length : 0)
                    + " gz=" + f.gzip.length));
            for (int p = 0; p < PHASES.length; p++) {
                long[] s = f.samples[p].clone();
                Arrays.sort(s);
                report.append(String.format("%16s", String.format("%.3f/%.3f",
                        s[s.length / 2] / 1e6, s[s.length * 3 / 4] / 1e6)));
            }
            report.append('\n');
            report.append(String.format("%-28s", "  first"));
            for (int p = 0; p < PHASES.length; p++) {
                report.append(String.format("%16.3f", f.first[p] / 1e6));
            }
            report.append('\n');
        }
        System.out.println(report);
        Files.write(new File(outDir, "bench." + label + ".txt").toPath(),
                report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void runOnce(Fixture f, ParseContext parser, long[] out, StringBuilder dump)
            throws Exception {
        long t0 = System.nanoTime();
        if (f.br != null) {
            readAll(brotli(new ByteArrayInputStream(f.br)));
        }
        long t1 = System.nanoTime();
        String text = Helpers.toString(new ByteArrayInputStream(f.json));
        long t2 = System.nanoTime();
        com.google.gson.JsonParser.parseString(text);
        long t3 = System.nanoTime();
        out[0] = t1 - t0;
        out[1] = t2 - t1;
        out[2] = t3 - t2;

        // The real converter path: stream -> text -> tree -> mapped VideoInfo.
        long p0 = System.nanoTime();
        VideoInfo info = new JsonPathTypeAdapter<VideoInfo>(parser, VideoInfo.class)
                .read(new ByteArrayInputStream(f.json));
        long p1 = System.nanoTime();
        out[4] = p1 - p0;
        out[3] = Math.max(0, out[4] - out[1] - out[2]);
        if (dump != null) {
            dump.append("== parsed\n").append(new GsonBuilder().serializeNulls().create().toJson(info))
                    .append('\n');
        }

        // What VideoInfoService does with a result before the transform (ring checks + the
        // player-result line). Only reads; the ring itself is not reproduced.
        long r0 = System.nanoTime();
        info.setClient(f.client);
        StringBuilder result = new StringBuilder();
        result.append(info.getRawPlayabilityStatus()).append(info.isUnplayable())
                .append(info.isBotCheckRequired()).append(info.isServerLoggedIn())
                .append(info.getDashManifestUrl() != null).append(info.getHlsManifestUrl() != null)
                .append(info.getServerAbrStreamingUrl() != null).append(info.getPlayabilityStatus())
                .append(info.isRent()).append(info.isLive());
        if (info.getAdaptiveFormats() != null) {
            for (VideoFormat format : info.getAdaptiveFormats()) {
                result.append(format.isBroken());
            }
        }
        long r1 = System.nanoTime();
        out[5] = r1 - r0;
        if (dump != null) {
            dump.append("== result\n").append(result).append('\n');
        }

        // decipherFormats without V8: the same holder walk, extract, apply sequence. TV_TIZEN's
        // ciphered params get a deterministic stand-in "solution" so the apply path runs.
        long x0 = System.nanoTime();
        List<VideoFormat> formats = new ArrayList<>();
        if (info.getAdaptiveFormats() != null) formats.addAll(info.getAdaptiveFormats());
        if (info.getRegularFormats() != null) formats.addAll(info.getRegularFormats());
        if (!formats.isEmpty()) {
            formats.get(0).getUrl(); // MediaHostPreconnect.warmUrlEarly(firstStreamUrl)
        }
        List<VideoUrlHolder> holders = new ArrayList<>();
        for (VideoFormat format : formats) holders.add(format.getUrlHolder());
        holders.add(info.getUrlHolder());
        List<String> n = new ArrayList<>();
        List<String> s = new ArrayList<>();
        for (VideoUrlHolder holder : holders) n.add(holder.getNParam());
        for (VideoUrlHolder holder : holders) s.add(holder.getSParam());
        boolean anyN = false;
        boolean anyS = false;
        for (String v : n) anyN |= v != null;
        for (String v : s) anyS |= v != null;
        if (anyN) {
            for (int i = 0; i < holders.size(); i++) {
                String v = n.get(i);
                holders.get(i).setNParam(v != null ? v + "x" : null);
            }
        }
        if (anyS) {
            for (int i = 0; i < holders.size(); i++) {
                String v = s.get(i);
                holders.get(i).setSignature(v != null ? new StringBuilder(v).reverse().toString() : null);
            }
        }
        info.setVisitorCookie(null);
        long x1 = System.nanoTime();
        out[6] = x1 - x0;
        if (dump != null) {
            dump.append("== transform\n");
            for (VideoUrlHolder holder : holders) {
                dump.append(holder.getUrl()).append('\n');
            }
        }

        long y0 = System.nanoTime();
        YouTubeMediaItemFormatInfo formatInfo = YouTubeMediaItemFormatInfo.from(info);
        formatInfo.setClickTrackingParams(null);
        boolean store = !formatInfo.isUnplayable() && formatInfo.containsMedia();
        long y1 = System.nanoTime();
        out[7] = y1 - y0;
        if (dump != null) {
            dump.append("== from store=").append(store).append('\n')
                    .append(new GsonBuilder().serializeNulls().create().toJson(formatInfo)).append('\n');
            for (MediaFormat format : formatInfo.getAdaptiveFormats()) {
                dump.append(format.getITag()).append(' ').append(format.getLanguage()).append(' ')
                        .append(format.getOtfInitUrl()).append(' ').append(format.getOtfTemplateUrl())
                        .append('\n');
            }
        }

        long g0 = System.nanoTime();
        readAll(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(f.gzip)));
        out[9] = System.nanoTime() - g0;

        long z0 = System.nanoTime();
        InputStream mpd = formatInfo.createMpdStream();
        byte[] mpdBytes = mpd != null ? readAll(mpd) : new byte[0];
        long z1 = System.nanoTime();
        out[8] = z1 - z0;
        if (dump != null) {
            dump.append("== mpd ").append(mpdBytes.length).append('\n')
                    .append(new String(mpdBytes, StandardCharsets.UTF_8)).append('\n');
        }
    }

    private static InputStream brotli(InputStream in) throws Exception {
        Class<?> type = Class.forName("org.brotli.dec.BrotliInputStream");
        Constructor<?> ctor = type.getConstructor(InputStream.class);
        return (InputStream) ctor.newInstance(in);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
