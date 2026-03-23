package monitoria.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;

public class MonitorConfigLoader {

    public static MonitorIAConfig load(String configPath) throws Exception {
        if (configPath == null || configPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Caminho do config não informado.");
        }
        File f = new File(configPath);
        if (!f.exists() || !f.isFile()) {
            throw new IllegalArgumentException("Arquivo config não encontrado: " + configPath);
        }
        ObjectMapper om = new ObjectMapper();
        return om.readValue(f, MonitorIAConfig.class);
    }
}

