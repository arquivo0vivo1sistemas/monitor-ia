package monitoria.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import monitoria.config.MonitorIAConfig;
import monitoria.ui.LogSink;
import utils.EmbeddingService;
import monitoria.av.Socks5FileTransferClient;
import utils.VideoTranscriber;
import utils.WhatsAppConfig;
import utils.WhatsAppService;
import json.JsonRepair;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.*;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Base64;

public class VideoOccurrencePipeline {

    private final MonitorIAConfig config;
    private final LogSink log;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    public VideoOccurrencePipeline(MonitorIAConfig config, LogSink log) {
        this.config = config;
        this.log = log;
    }

    public void processSingleVideo(Path videoPath) throws Exception {
        if (videoPath == null || !Files.exists(videoPath)) return;

        File videoFile = videoPath.toFile();
        String videoFileName = videoFile.getName();
        String cameraName = extrairCameraName(videoFile);

        log.log("====================================================");
        log.log("Iniciando vídeo: " + videoFileName + " | camera=" + cameraName);

        // 1) validações
        int fps = VideoTranscriber.obterFpsVideo(videoFile);
        if (fps < config.minFps) {
            throw new IllegalArgumentException("FPS insuficiente: " + fps + " < " + config.minFps);
        }

        // validar resolução usando primeiro frame
        Resolution res = validarResolucao(videoFile);
        if (res.width < config.minWidth || res.height < config.minHeight) {
            throw new IllegalArgumentException("Resolução insuficiente: " + res.width + "x" + res.height +
                    " < minimo " + config.minWidth + "x" + config.minHeight);
        }
        log.log("Validação OK: fps=" + fps + " | res=" + res.width + "x" + res.height);

        double duracaoTotal = VideoTranscriber.obterDuracaoSegundos(videoFile);
        if (duracaoTotal <= 0) duracaoTotal = 0;
        log.log("Duração estimada (s): " + String.format(Locale.US, "%.2f", duracaoTotal));

        // 2) extrair frames candidatos por slots
        Path tempDir = Files.createTempDirectory("monitoria_frames_");
        File[] extracted = null;
        try {
            List<SelectedFrame> framesKeptQuality = extrairESelecionarPorQualidade(videoFile, fps, duracaoTotal, tempDir);
            if (framesKeptQuality.size() < config.quality.minimoFramesSelecionados) {
                throw new IllegalStateException("Conteudo insuficiente após qualidade. framesSelecionados=" +
                        framesKeptQuality.size() + ", minimo=" + config.quality.minimoFramesSelecionados);
            }

            log.log("Frames após qualidade: " + framesKeptQuality.size());

            // 3) similaridade por embeddings => remover duplicatas
            List<SelectedFrame> framesKept = filtrarSimilaridadePorEmbeddings(framesKeptQuality);
            if (framesKept.isEmpty()) {
                throw new IllegalStateException("Nenhum frame passou no filtro de similaridade.");
            }
            log.log("Frames após similaridade: " + framesKept.size());

            // 4) descrição IA (prompt do config)
            List<String> descricoes = new ArrayList<>();
            for (int i = 0; i < framesKept.size(); i++) {
                SelectedFrame f = framesKept.get(i);
                log.log("Gerando descrição IA do frame_ordenacao[" + i + "] slot=" + f.slotIndex +
                        " t=" + String.format(Locale.US, "%.2f", f.timestampSec));
                String desc = descreverFrameComIA(i, f.base64Png, cameraName);
                descricoes.add(desc);
            }

            // 5) detecção de eventos (prompt do config)
            EventsResult eventsResult = detectarEventosComIA(cameraName, videoFileName, duracaoTotal, framesKept, descricoes);
            if (eventsResult == null || eventsResult.eventosDetectados.isEmpty() || !eventsResult.anyEvent) {
                log.log("Nenhum evento relevante detectado. Encerrando vídeo sem alerta.");
                return;
            }

            log.log("Eventos detectados: " + eventsResult.eventosDetectados.size());

            // 6) recorte time_clip baseado em frames relevantes
            Set<Integer> relevantFrameIndices = eventsResult.framesRelevantUnion;
            if (relevantFrameIndices == null || relevantFrameIndices.isEmpty()) {
                // fallback: usar todos os frames
                relevantFrameIndices = new HashSet<>();
                for (int i = 0; i < framesKept.size(); i++) relevantFrameIndices.add(i);
            }

            double firstTs = Double.MAX_VALUE;
            double lastTs = Double.MIN_VALUE;
            for (int idx : relevantFrameIndices) {
                if (idx >= 0 && idx < framesKept.size()) {
                    firstTs = Math.min(firstTs, framesKept.get(idx).timestampSec);
                    lastTs = Math.max(lastTs, framesKept.get(idx).timestampSec);
                }
            }
            if (firstTs == Double.MAX_VALUE || lastTs == Double.MIN_VALUE) {
                throw new IllegalStateException("Não foi possível definir intervalos do clip a partir dos frames relevantes.");
            }

            double startSec = Math.max(0, firstTs - config.clip.marginSeconds);
            double endSec = duracaoTotal > 0 ? Math.min(duracaoTotal, lastTs + config.clip.marginSeconds) : (lastTs + config.clip.marginSeconds);
            double clipDuration = Math.max(0.1, endSec - startSec);

            log.log("Gerando time_clip: start=" + String.format(Locale.US, "%.2f", startSec) +
                    "s duration=" + String.format(Locale.US, "%.2f", clipDuration) + "s");

            Path clipFile = gerarClipComFFmpeg(videoFile, startSec, clipDuration, tempDir);

            // 7) alertar operador no WhatsApp
            String msg = montarMensagemAlertas(cameraName, eventsResult, startSec, endSec);
            // 8) enviar clip para Arquivo Vivo via socket (Socks5FileTransferClient)
            enviarClipParaArquivoVivo(clipFile);

            // 7) alertar operador no WhatsApp (após clip enviado com sucesso)
            enviarWhatsApp(msg);

            log.log("Concluído vídeo com eventos. clip=" + clipFile.toAbsolutePath());

        } finally {
            try {
                limparDiretorio(tempDir);
            } catch (Exception ignored) {
            }
        }
    }

    private String montarMensagemAlertas(String cameraName, EventsResult result, double startSec, double endSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALERTA VIDEO\n");
        sb.append("Camera: ").append(cameraName).append("\n");
        sb.append("Janela: ").append(String.format(Locale.US, "%.1f", startSec))
                .append("s - ").append(String.format(Locale.US, "%.1f", endSec)).append("s\n");
        sb.append("Eventos:\n");
        for (EventsResult.Evento ev : result.eventosDetectados) {
            sb.append("- ").append(ev.tipo);
            if (ev.severidade != null && !ev.severidade.trim().isEmpty()) sb.append(" (").append(ev.severidade.trim()).append(")");
            if (ev.acao != null && !ev.acao.trim().isEmpty()) sb.append(": ").append(ev.acao.trim());
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private void enviarWhatsApp(String message) throws Exception {
        if (config.whatsapp == null || config.whatsapp.url == null || config.whatsapp.url.trim().isEmpty()) {
            log.log("WhatsApp não configurado (url vazia). Pulando alerta.");
            return;
        }
        if (config.whatsapp.apiKey == null || config.whatsapp.apiKey.trim().isEmpty()) {
            log.log("WhatsApp não configurado (apiKey vazia). Pulando alerta.");
            return;
        }
        if (config.whatsapp.groupChatId == null || config.whatsapp.groupChatId.trim().isEmpty()) {
            log.log("WhatsApp não configurado (groupChatId vazia). Pulando alerta.");
            return;
        }

        WhatsAppConfig wa = new WhatsAppConfig();
        wa.url = config.whatsapp.url;
        wa.apiKey = config.whatsapp.apiKey;

        WhatsAppService service = new WhatsAppService();
        service.enviarMensagemParaGrupo(wa, config.whatsapp.groupChatId, message);
    }

    private void enviarClipParaArquivoVivo(Path clipFile) throws Exception {
        if (clipFile == null || !Files.exists(clipFile)) return;
        if (config.arquivoVivo == null) throw new IllegalStateException("Arquivo Vivo socket não configurado.");
        if (config.arquivoVivo.targetHost == null || config.arquivoVivo.targetHost.trim().isEmpty()) {
            throw new IllegalStateException("arquivoVivo.targetHost não configurado.");
        }

        Path sendDir = clipFile.getParent().resolve("sock_send");
        Files.createDirectories(sendDir);

        // copiar para evitar que o recorte original seja movido/alterado pelo cliente de socket
        Path destino = sendDir.resolve(clipFile.getFileName().toString());
        Files.copy(clipFile, destino, StandardCopyOption.REPLACE_EXISTING);

        Socks5FileTransferClient client;
        if (config.arquivoVivo.useProxy) {
            client = new Socks5FileTransferClient(
                    config.arquivoVivo.proxyHost,
                    config.arquivoVivo.proxyPort,
                    config.arquivoVivo.proxyUsername,
                    config.arquivoVivo.proxyPassword,
                    config.arquivoVivo.targetHost,
                    config.arquivoVivo.targetPort
            );
        } else {
            client = new Socks5FileTransferClient(config.arquivoVivo.targetHost, config.arquivoVivo.targetPort);
        }

        String chatId = config.arquivoVivo.chatId != null ? config.arquivoVivo.chatId : "";
        String numeroWhats = config.arquivoVivo.numeroWhats != null ? config.arquivoVivo.numeroWhats : "";

        // intervalSegundosEntreVarreduras=0 => encerra quando não houver arquivos.
        client.transferirArquivosDoDiretorio(sendDir.toString(), ".mp4", chatId, numeroWhats, 0);
    }

    private Path gerarClipComFFmpeg(File videoFile, double startSec, double clipDurationSec, Path tempDir) throws Exception {
        VideoTranscriber.verificarFFmpegInstalado();
        Path outDir = tempDir.resolve("clips");
        Files.createDirectories(outDir);

        String baseName = videoFile.getName();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);

        DecimalFormat df = new DecimalFormat("0.00");
        String outName = baseName + "_clip_" + df.format(startSec).replace(",", ".") + "s_" + df.format(clipDurationSec).replace(",", ".") + "s.mp4";
        Path outFile = outDir.resolve(outName);

        String ffmpegPath = VideoTranscriber.verificarFFmpegInstalado();
        if (ffmpegPath == null) throw new IllegalStateException("FFmpeg não instalado.");

        // Recorte: por MVP, usar -c copy (mais rápido). Ajuste para reencode se necessário.
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(String.valueOf(startSec));
        cmd.add("-i");
        cmd.add(videoFile.getAbsolutePath());
        cmd.add("-t");
        cmd.add(String.valueOf(clipDurationSec));
        if ("reencode".equalsIgnoreCase(config.ffmpeg.clipMode)) {
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("veryfast");
            cmd.add("-c:a");
            cmd.add("aac");
        } else {
            cmd.add("-c");
            cmd.add("copy");
        }
        cmd.add("-avoid_negative_ts");
        cmd.add("make_zero");
        cmd.add(outFile.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append("\n");
            }
        }

        int exit = p.waitFor();
        if (exit != 0 || !Files.exists(outFile)) {
            throw new IOException("Falha ao recortar clip com FFmpeg. Exit=" + exit + "\n" + out.toString());
        }
        return outFile;
    }

    private Resolution validarResolucao(File videoFile) throws Exception {
        String base64 = VideoTranscriber.extrairPrimeiroFrame(videoFile);
        if (base64 == null || base64.trim().isEmpty()) {
            throw new IllegalStateException("Não foi possível extrair thumbnail para validar resolução.");
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(bais);
            if (img == null) throw new IllegalStateException("ImageIO não conseguiu ler frame.");
            return new Resolution(img.getWidth(), img.getHeight());
        }
    }

    private static class Resolution {
        int width;
        int height;

        Resolution(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private String extrairCameraName(File videoFile) {
        String name = videoFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    private List<SelectedFrame> extrairESelecionarPorQualidade(File videoFile, int fpsOrig, double duracaoTotalSec, Path tempDir) throws Exception {
        String ffmpegPath = VideoTranscriber.verificarFFmpegInstalado();
        if (ffmpegPath == null) throw new IllegalStateException("FFmpeg não está instalado.");

        int extractFps = Math.max(1, Math.min(config.sampling.maxExtractFps, fpsOrig));
        if (extractFps < 1) extractFps = 1;
        log.log("Extract candidates: fpsExtract=" + extractFps + " | intervalSeconds=" + config.sampling.intervalSeconds);

        Path framesDir = tempDir.resolve("frames");
        Files.createDirectories(framesDir);

        // extrair frames
        Path framePattern = framesDir.resolve("frame_%06d.png");
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(videoFile.getAbsolutePath());
        cmd.add("-vf");
        cmd.add("fps=" + extractFps);
        cmd.add(framePattern.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder ffOut = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                ffOut.append(line).append("\n");
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("FFmpeg falhou ao extrair frames. Exit=" + exit + "\n" + ffOut.toString());
        }

        // listar frames extraídos e ordenar
        List<Path> frameFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.list(framesDir)) {
            st.filter(pth -> pth.getFileName().toString().startsWith("frame_") && pth.getFileName().toString().endsWith(".png"))
                    .forEach(frameFiles::add);
        }
        if (frameFiles.isEmpty()) throw new IllegalStateException("FFmpeg não gerou frames.");

        frameFiles.sort(Comparator.comparingInt(framePath -> parseFrameIndex(framePath.getFileName().toString())));

        // seleção: 1 frame por slot (slotIndex = floor(t/interval))
        double interval = config.sampling.intervalSeconds;
        Map<Integer, SelectedFrame> selectedBySlot = new HashMap<>();

        VideoQualityFilter qf = new VideoQualityFilter(config.quality);
        for (Path frameFile : frameFiles) {
            int idx = parseFrameIndex(frameFile.getFileName().toString());
            double timestamp = idx / (double) extractFps; // aproximação
            int slotIndex = (int) Math.floor(timestamp / interval);
            if (selectedBySlot.containsKey(slotIndex)) continue;

            BufferedImage img = ImageIO.read(frameFile.toFile());
            if (img == null) continue;

            if (!qf.passaQualidade(img)) continue;

            String base64 = fileToBase64(frameFile.toFile());
            SelectedFrame sf = new SelectedFrame(slotIndex, idx, timestamp, base64);
            selectedBySlot.put(slotIndex, sf);

            log.log("Slot " + slotIndex + ": frame idx=" + idx + " t=" + String.format(Locale.US, "%.2f", timestamp) + " OK");
        }

        List<SelectedFrame> selected = new ArrayList<>(selectedBySlot.values());
        selected.sort(Comparator.comparingInt(a -> a.slotIndex));

        if (duracaoTotalSec > 0) {
            int expectedSlots = (int) Math.ceil(duracaoTotalSec / interval);
            log.log("Slots esperados ~ " + expectedSlots + " | slots selecionados=" + selected.size());
        }
        return selected;
    }

    private int parseFrameIndex(String fileName) {
        // frame_000001.png => 1 (indice zero-based na prática? aqui usamos inteiro do ffmpeg)
        try {
            String n = fileName;
            int us = n.indexOf('_');
            int dot = n.lastIndexOf('.');
            if (us >= 0 && dot > us) {
                String digits = n.substring(us + 1, dot);
                return Integer.parseInt(digits);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private List<SelectedFrame> filtrarSimilaridadePorEmbeddings(List<SelectedFrame> framesKeptQuality) throws Exception {
        if (framesKeptQuality == null || framesKeptQuality.isEmpty()) return Collections.emptyList();

        List<float[]> embeddings = new ArrayList<>();
        List<Integer> frameSlotIndices = new ArrayList<>();

        for (SelectedFrame f : framesKeptQuality) {
            float[] emb = EmbeddingService.gerarEmbeddingImagemHuggingFace(f.base64Png, null);
            embeddings.add(emb);
            frameSlotIndices.add(f.slotIndex);
        }

        List<Integer> indicesManter = EmbeddingService.filtrarIndicesPorSimilaridade(
                embeddings,
                config.similarity.limiarSimilaridadeImagem,
                frameSlotIndices
        );

        List<SelectedFrame> out = new ArrayList<>();
        for (int idx : indicesManter) {
            if (idx >= 0 && idx < framesKeptQuality.size()) out.add(framesKeptQuality.get(idx));
        }
        return out;
    }

    private String descreverFrameComIA(int orderIndex, String frameBase64, String cameraName) throws Exception {
        VisionDescription descClient = new VisionDescription(config.vision, http, objectMapper, log);
        return descClient.descrever(frameBase64);
    }

    private EventsResult detectarEventosComIA(
            String cameraName,
            String videoFileName,
            double duracaoTotal,
            List<SelectedFrame> framesKept,
            List<String> descricoes) throws Exception {

        EventsDetectorClient detector = new EventsDetectorClient(config.events, config.vision, http, objectMapper, log);
        return detector.detect(cameraName, videoFileName, duracaoTotal, framesKept, descricoes);
    }

    private static String fileToBase64(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void limparDiretorio(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> st = Files.walk(dir)) {
            st.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private static class SelectedFrame {
        final int slotIndex;
        final int frameIndexExtract; // indice do frame no ffmpeg
        final double timestampSec;    // aproximado
        final String base64Png;

        SelectedFrame(int slotIndex, int frameIndexExtract, double timestampSec, String base64Png) {
            this.slotIndex = slotIndex;
            this.frameIndexExtract = frameIndexExtract;
            this.timestampSec = timestampSec;
            this.base64Png = base64Png;
        }
    }

    /**
     * Filtragem por qualidade (blur/entropia/pixels extremos/objeto).
     * Reimplementa a lógica existente no SOCKWHAPI (ChatGPTService).
     */
    private static class VideoQualityFilter {
        private final MonitorIAConfig.Quality q;

        VideoQualityFilter(MonitorIAConfig.Quality q) {
            this.q = q;
        }

        boolean passaQualidade(BufferedImage image) {
            double lap = calcularBlurLaplaciano(image);
            double ent = calcularEntropia(image);
            double pxExt = calcularPixelsExtremos(image);
            double obj = calcularTamanhoMaiorObjeto(image);
            return lap >= q.laplacianoMin
                    && ent >= q.entropiaMin
                    && pxExt <= q.pixelsExtremosMaxPercent
                    && obj >= q.objetoMinPercent;
        }

        /** Variancia do Laplaciano (blur detector). Quanto maior, mais nítida. */
        private double calcularBlurLaplaciano(BufferedImage image) {
            int w = image.getWidth();
            int h = image.getHeight();
            if (w < 3 || h < 3) return 0;
            int[][] gray = new int[w][h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    gray[x][y] = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                }
            }
            long soma = 0, somaQuadrados = 0;
            int count = 0;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int lap = 4 * gray[x][y] - gray[x - 1][y] - gray[x + 1][y] - gray[x][y - 1] - gray[x][y + 1];
                    soma += lap;
                    somaQuadrados += (long) lap * lap;
                    count++;
                }
            }
            if (count == 0) return 0;
            double media = (double) soma / count;
            return ((double) somaQuadrados / count) - (media * media);
        }

        /** Entropia em bits (base 2). */
        private double calcularEntropia(BufferedImage image) {
            int w = image.getWidth();
            int h = image.getHeight();
            int[] hist = new int[256];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    int g = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    hist[Math.min(255, g)]++;
                }
            }
            int total = w * h;
            if (total == 0) return 0;
            double ent = 0;
            for (int i = 0; i < 256; i++) {
                if (hist[i] > 0) {
                    double p = (double) hist[i] / total;
                    ent -= p * (Math.log(p) / Math.log(2));
                }
            }
            return ent;
        }

        /** Percentual de pixels em 0 ou 255 (extremos). */
        private double calcularPixelsExtremos(BufferedImage image) {
            int w = image.getWidth();
            int h = image.getHeight();
            int extremos = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    int g = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (g <= 5 || g >= 250) extremos++;
                }
            }
            return (w * h) > 0 ? 100.0 * extremos / (w * h) : 0;
        }

        /** Percentual da imagem ocupada pelo maior componente conectado (objeto relevante). */
        private double calcularTamanhoMaiorObjeto(BufferedImage image) {
            int w = image.getWidth();
            int h = image.getHeight();
            if (w < 2 || h < 2) return 0;
            int[][] gray = new int[w][h];
            int media = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    gray[x][y] = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    media += gray[x][y];
                }
            }
            media /= (w * h);
            boolean[][] bin = new boolean[w][h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bin[x][y] = Math.abs(gray[x][y] - media) > 25;
                }
            }
            boolean[][] visit = new boolean[w][h];
            int maxArea = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (bin[x][y] && !visit[x][y]) {
                        int a = floodFillIterativo(bin, visit, w, h, x, y);
                        if (a > maxArea) maxArea = a;
                    }
                }
            }
            return (w * h) > 0 ? 100.0 * maxArea / (w * h) : 0;
        }

        private int floodFillIterativo(boolean[][] bin, boolean[][] visit, int w, int h, int startX, int startY) {
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{startX, startY});
            int area = 0;
            while (!stack.isEmpty()) {
                int[] p = stack.pop();
                int x = p[0], y = p[1];
                if (x < 0 || x >= w || y < 0 || y >= h || !bin[x][y] || visit[x][y]) continue;
                visit[x][y] = true;
                area++;
                stack.push(new int[]{x - 1, y});
                stack.push(new int[]{x + 1, y});
                stack.push(new int[]{x, y - 1});
                stack.push(new int[]{x, y + 1});
            }
            return area;
        }
    }

    private static class EventsResult {
        static class Evento {
            public String tipo;
            public String severidade;
            public String justificativa;
            public String acao;
            public List<Integer> framesRelevantes = new ArrayList<>();
        }

        public boolean anyEvent;
        public List<Evento> eventosDetectados = new ArrayList<>();
        public Set<Integer> framesRelevantUnion = new HashSet<>();
    }

    private static class VisionDescription {
        private final MonitorIAConfig.Vision v;
        private final OkHttpClient http;
        private final ObjectMapper om;
        private final LogSink log;

        VisionDescription(MonitorIAConfig.Vision v, OkHttpClient http, ObjectMapper om, LogSink log) {
            this.v = v;
            this.http = http;
            this.om = om;
            this.log = log;
        }

        String descrever(String frameBase64) throws Exception {
            if (v == null) throw new IllegalStateException("vision config ausente.");
            if (v.apiKey == null || v.apiKey.trim().isEmpty()) throw new IllegalStateException("vision.apiKey ausente.");
            if (v.DESCREVER_FRAME == null || v.DESCREVER_FRAME.trim().isEmpty()) throw new IllegalStateException("vision.DESCREVER_FRAME ausente.");

            String openaiUrl = v.openaiUrl != null && !v.openaiUrl.trim().isEmpty() ? v.openaiUrl.trim() : "https://api.openai.com";
            if (openaiUrl.endsWith("/")) openaiUrl = openaiUrl.substring(0, openaiUrl.length() - 1);
            String url = openaiUrl + "/v1/chat/completions";

            String model = v.model != null && !v.model.trim().isEmpty() ? v.model.trim() : "gpt-4o-mini";

            String prompt = v.DESCREVER_FRAME.trim();

            ObjectNode body = om.createObjectNode();
            body.put("model", model);
            ArrayNode messages = om.createArrayNode();
            ObjectNode user = om.createObjectNode();
            user.put("role", "user");
            ArrayNode contentArr = om.createArrayNode();

            ObjectNode text = om.createObjectNode();
            text.put("type", "text");
            text.put("text", prompt);
            contentArr.add(text);

            ObjectNode imageNode = om.createObjectNode();
            imageNode.put("type", "image_url");
            ObjectNode imageUrl = om.createObjectNode();
            imageUrl.put("url", "data:image/png;base64," + frameBase64);
            imageNode.set("image_url", imageUrl);
            contentArr.add(imageNode);

            user.set("content", contentArr);
            messages.add(user);
            body.set("messages", messages);
            body.put("temperature", 0.1);
            body.put("max_tokens", 1500);
            ObjectNode responseFormat = om.createObjectNode();
            responseFormat.put("type", "json_object");
            body.set("response_format", responseFormat);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + v.apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "";
                    throw new IOException("Vision (descricao) falhou: HTTP " + response.code() + " - " + err);
                }
                String jsonResponse = response.body() != null ? response.body().string() : "";
                JsonNode root = om.readTree(jsonResponse);
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                if (content == null) content = "";
                content = content.trim();
                if (content.isEmpty()) return "";

                String reparado = JsonRepair.extrairERepararJson(content);
                JsonNode node = om.readTree(reparado);
                String descricao = node.path("descricao").asText("");
                if (descricao != null) descricao = descricao.trim();
                if (descricao == null || descricao.isEmpty()) {
                    // fallback: se o modelo retornou outro campo
                    descricao = node.path("text").asText("");
                }
                return descricao != null ? descricao : "";
            }
        }
    }

    private static class EventsDetectorClient {
        private final MonitorIAConfig.Events ev;
        private final MonitorIAConfig.Vision vision;
        private final OkHttpClient http;
        private final ObjectMapper om;
        private final LogSink log;

        EventsDetectorClient(MonitorIAConfig.Events ev, MonitorIAConfig.Vision vision, OkHttpClient http, ObjectMapper om, LogSink log) {
            this.ev = ev;
            this.vision = vision;
            this.http = http;
            this.om = om;
            this.log = log;
        }

        EventsResult detect(String cameraName, String videoFileName, double duracaoTotal, List<SelectedFrame> framesKept, List<String> descricoes) throws Exception {
            if (ev == null) throw new IllegalStateException("events config ausente.");
            if (ev.apiKey == null || ev.apiKey.trim().isEmpty()) {
                // fallback: pode reaproveitar vision.apiKey
                if (vision != null && vision.apiKey != null && !vision.apiKey.trim().isEmpty()) {
                    // ok
                } else {
                    throw new IllegalStateException("events.apiKey ausente e vision.apiKey ausente.");
                }
            }
            if (ev.DETECCAO_DE_EVENTOS_E_ACAO == null || ev.DETECCAO_DE_EVENTOS_E_ACAO.trim().isEmpty()) {
                throw new IllegalStateException("events.DETECCAO_DE_EVENTOS_E_ACAO ausente.");
            }

            String apiKey = ev.apiKey != null && !ev.apiKey.trim().isEmpty() ? ev.apiKey : vision.apiKey;
            String openaiUrl = ev.openaiUrl != null && !ev.openaiUrl.trim().isEmpty() ? ev.openaiUrl.trim() : "https://api.openai.com";
            if (openaiUrl.endsWith("/")) openaiUrl = openaiUrl.substring(0, openaiUrl.length() - 1);
            String url = openaiUrl + "/v1/chat/completions";

            String model = ev.model != null && !ev.model.trim().isEmpty() ? ev.model.trim() : "gpt-4o-mini";

            StringBuilder framesData = new StringBuilder();
            framesData.append("VIDEO_FILE: ").append(videoFileName).append("\n");
            framesData.append("CAMERA: ").append(cameraName).append("\n");
            framesData.append("DURACAO_SEC: ").append(String.format(Locale.US, "%.2f", duracaoTotal)).append("\n\n");
            framesData.append("FRAMES_KEPT (indices na ordem recebida):\n");
            for (int i = 0; i < framesKept.size(); i++) {
                SelectedFrame f = framesKept.get(i);
                String d = (i < descricoes.size() ? descricoes.get(i) : "");
                framesData.append("[").append(i).append("] slot=").append(f.slotIndex)
                        .append(" t=").append(String.format(Locale.US, "%.2f", f.timestampSec))
                        .append(" descricao=").append(d == null ? "" : d).append("\n");
            }

            String userPrompt = ev.DETECCAO_DE_EVENTOS_E_ACAO.trim() + "\n\nDADOS:\n" + framesData.toString();

            ObjectNode body = om.createObjectNode();
            body.put("model", model);
            ArrayNode messages = om.createArrayNode();
            ObjectNode user = om.createObjectNode();
            user.put("role", "user");
            user.put("content", userPrompt);
            messages.add(user);
            body.set("messages", messages);
            body.put("temperature", 0.2);
            body.put("max_tokens", 2000);
            ObjectNode responseFormat = om.createObjectNode();
            responseFormat.put("type", "json_object");
            body.set("response_format", responseFormat);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "";
                    throw new IOException("Vision (eventos) falhou: HTTP " + response.code() + " - " + err);
                }
                String jsonResponse = response.body() != null ? response.body().string() : "";
                JsonNode root = om.readTree(jsonResponse);
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                if (content == null) content = "";
                content = content.trim();
                if (content.isEmpty()) return new EventsResult();

                String reparado = JsonRepair.extrairERepararJson(content);
                JsonNode node = om.readTree(reparado);

                EventsResult out = new EventsResult();
                out.anyEvent = node.path("any_event").asBoolean(false);

                JsonNode eventosArr = node.path("eventos_detectados");
                if (!eventosArr.isArray()) {
                    // fallback: alguns prompts podem usar "eventos"
                    eventosArr = node.path("eventos");
                }

                if (eventosArr.isArray()) {
                    for (JsonNode evNode : eventosArr) {
                        EventsResult.Evento evObj = new EventsResult.Evento();
                        evObj.tipo = evNode.path("tipo").asText("");
                        evObj.severidade = evNode.path("severidade").asText("");
                        evObj.justificativa = evNode.path("justificativa").asText("");
                        evObj.acao = evNode.path("acao").asText("");

                        boolean presente = evNode.has("presente") ? evNode.path("presente").asBoolean(false) : true;
                        if (!presente) continue;

                        JsonNode framesRel = evNode.path("frames_relevantes");
                        if (framesRel.isArray()) {
                            for (JsonNode fr : framesRel) {
                                int idx = fr.asInt(-1);
                                if (idx >= 0) {
                                    evObj.framesRelevantes.add(idx);
                                    out.framesRelevantUnion.add(idx);
                                }
                            }
                        }
                        out.eventosDetectados.add(evObj);
                    }
                }

                // se any_event estiver false mas eventos vierem: considerar any_event via presença
                if (!out.anyEvent && !out.eventosDetectados.isEmpty()) out.anyEvent = true;
                return out;
            }
        }
    }
}

