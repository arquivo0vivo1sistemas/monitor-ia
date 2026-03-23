package monitoria;

import monitoria.config.MonitorIAConfig;
import monitoria.config.MonitorConfigLoader;
import monitoria.core.MonitorRunner;
import monitoria.ui.MonitorUI;

import java.io.File;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--config".equals(arg) || "-c".equals(arg)) {
                    if (i + 1 < args.length) configPath = args[++i];
                } else if (arg.startsWith("--config=")) {
                    configPath = arg.substring("--config=".length());
                }
            }

            if (configPath == null) {
                // fallback: monitor-config.json no diretório atual
                File f = new File("monitor-config.json");
                configPath = f.getAbsolutePath();
            }

            MonitorUI ui = new MonitorUI();
            ui.setStatus("Carregando config...");

            MonitorIAConfig config = MonitorConfigLoader.load(configPath);
            if (config == null) throw new IllegalStateException("Config nula.");
            if (config.baseDir == null || config.baseDir.trim().isEmpty()) {
                throw new IllegalArgumentException("baseDir não configurado.");
            }

            ui.log("Config carregada: " + configPath);
            ui.log("BaseDir: " + config.baseDir);

            MonitorRunner runner = new MonitorRunner(config, ui);
            ui.setStatus("Rodando monitor...");
            runner.start();

        } catch (Exception e) {
            System.err.println("Erro ao iniciar MONITOR_IA: " + e.getMessage());
            e.printStackTrace();
            return;
        }
    }
}

