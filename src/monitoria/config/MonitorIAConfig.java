package monitoria.config;

/**
 * Configuração do MONITOR_IA via JSON.
 * Mantida propositalmente simples para o MVP.
 */
public class MonitorIAConfig {

    public String baseDir;
    public int scanIntervalSeconds = 10;

    public String processedSubdir = "PROCESSADOS";
    public String errorSubdir = "ERROS";
    public String inProgressSubdir = "PROCESSANDO";

    public int minFps = 15;
    public int minWidth = 474;
    public int minHeight = 850;

    public FrameSampling sampling = new FrameSampling();
    public Quality quality = new Quality();
    public Similarity similarity = new Similarity();

    public Vision vision = new Vision();
    public Events events = new Events();

    public WhatsAppConfig whatsapp = new WhatsAppConfig();
    public ArquivoVivoSocket arquivoVivo = new ArquivoVivoSocket();

    public ClipConfig clip = new ClipConfig();
    public FfmpegConfig ffmpeg = new FfmpegConfig();

    public static class FrameSampling {
        /**
         * Intervalo (em segundos) para “slots” e para montar o time_clip.
         * Ex.: intervalSeconds=10 => 1 frame representativo a cada 10s.
         */
        public double intervalSeconds = 10.0;

        /**
         * Limite (FPS) da taxa com que o FFmpeg extrai frames candidatos.
         * Quanto maior, mais candidatos por slot (melhor chance de qualidade, custo maior).
         */
        public int maxExtractFps = 30;
    }

    public static class Quality {
        public double laplacianoMin = 25.0;
        public double entropiaMin = 3.0;
        public double pixelsExtremosMaxPercent = 70.0;
        public double objetoMinPercent = 1.5;

        public int minimoFramesSelecionados = 4;
    }

    public static class Similarity {
        /**
         * limiar de similaridade por embedding (cosseno).
         * Se similaridade >= limiar => considera duplicata e remove.
         */
        public double limiarSimilaridadeImagem = 0.90;
    }

    public static class Vision {
        public String apiKey;
        public String openaiUrl = "https://api.openai.com";
        public String model = "gpt-4o-mini";

        /**
         * Prompt para descrever o frame em JSON.
         * Deve pedir saída no formato JSON (response_format json_object).
         * A implementação espera uma chave "descricao" (string).
         */
        public String DESCREVER_FRAME;
    }

    public static class Events {
        public String apiKey;
        public String openaiUrl = "https://api.openai.com";
        public String model = "gpt-4o-mini";

        /**
         * Prompt para detecção de eventos.
         * A implementação espera JSON com:
         * - any_event (boolean)
         * - eventos_detectados (array) onde cada item pode conter:
         *   tipo, severidade, justificativa, acao
         *   frames_relevantes (array de índices na lista de frames_kept)
         */
        public String DETECCAO_DE_EVENTOS_E_ACAO;
    }

    public static class WhatsAppConfig {
        public String url;
        public String apiKey;
        public String groupChatId;
    }

    public static class ArquivoVivoSocket {
        public String targetHost;
        public int targetPort;

        /**
         * chatId e numeroWhats são enviados ao SOCKWHAPI no protocolo.
         */
        public String chatId;
        public String numeroWhats;

        /**
         * Se false, envia modo direto sem proxy.
         */
        public boolean useProxy = false;
        public String proxyHost;
        public int proxyPort;
        public String proxyUsername;
        public String proxyPassword;
    }

    public static class ClipConfig {
        /**
         * Margem (em segundos) antes/depois do trecho.
         */
        public double marginSeconds = 8.0;
    }

    public static class FfmpegConfig {
        /**
         * Implementação do recorte:
         * - "copy" => -c copy (mais rápido, pode perder precisão em keyframes)
         * - "reencode" => reencode (mais lento)
         */
        public String clipMode = "copy";
    }
}

