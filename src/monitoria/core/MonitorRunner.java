package monitoria.core;

import monitoria.config.MonitorIAConfig;
import monitoria.ui.LogSink;
import monitoria.ui.MonitorUI;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorRunner {

    private final MonitorIAConfig config;
    private final MonitorUI ui;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public MonitorRunner(MonitorIAConfig config, MonitorUI ui) {
        this.config = config;
        this.ui = ui;
    }

    public void start() {
        Thread t = new Thread(this::loop, "monitoria-runner");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        Path base = Paths.get(config.baseDir);
        Path processedDir = base.resolve(config.processedSubdir);
        Path errorDir = base.resolve(config.errorSubdir);
        Path inProgressDir = base.resolve(config.inProgressSubdir);

        try {
            Files.createDirectories(processedDir);
            Files.createDirectories(errorDir);
            Files.createDirectories(inProgressDir);
        } catch (Exception e) {
            ui.logError("Falha ao criar diretórios (processed/erros/processando).", e);
            return;
        }

        VideoOccurrencePipeline pipeline = new VideoOccurrencePipeline(config, ui);

        ui.log("Diretórios:");
        ui.log(" - base=" + base.toAbsolutePath());
        ui.log(" - processados=" + processedDir.toAbsolutePath());
        ui.log(" - erros=" + errorDir.toAbsolutePath());
        ui.log(" - processando=" + inProgressDir.toAbsolutePath());

        while (!stopRequested.get()) {
            if (!ui.isRunning()) {
                ui.setStatus("Parado (UI). Aguardando...");
                sleepSeconds(2);
                continue;
            }

            try {
                List<Path> candidates = listarMp4Pendentes(base, processedDir, errorDir, inProgressDir);
                candidates.sort(Comparator.comparingLong(this::creationTimeMillis));

                if (candidates.isEmpty()) {
                    ui.setStatus("Aguardando novos MP4...");
                    sleepSeconds(Math.max(1, config.scanIntervalSeconds));
                    continue;
                }

                Path next = candidates.get(0);
                ui.setStatus("Processando: " + next.getFileName());

                Path moved = moverPara(next, inProgressDir);
                if (moved == null) {
                    // Se mover falhar, tenta novamente no próximo ciclo
                    sleepSeconds(1);
                    continue;
                }

                try {
                    pipeline.processSingleVideo(moved);
                    moverPara(moved, processedDir);
                } catch (Exception e) {
                    ui.logError("Erro ao processar vídeo: " + moved.toAbsolutePath(), e);
                    try {
                        moverPara(moved, errorDir);
                    } catch (Exception ex) {
                        ui.logError("Falha ao mover vídeo para ERROS: " + moved.toAbsolutePath(), ex);
                    }
                }

            } catch (Exception e) {
                ui.logError("Erro no ciclo de monitoramento.", e);
                sleepSeconds(Math.max(1, Math.min(30, config.scanIntervalSeconds)));
            }
        }
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private long creationTimeMillis(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            FileTime ct = attrs.creationTime();
            if (ct != null && ct.toMillis() > 0) return ct.toMillis();
        } catch (Exception ignored) {
        }
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }

    private List<Path> listarMp4Pendentes(Path base, Path processedDir, Path errorDir, Path inProgressDir) throws IOException {
        List<Path> out = new ArrayList<>();

        Files.walk(base)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp4"))
                .forEach(p -> {
                    // Excluir subdirs de controle
                    Path parent = p.getParent();
                    if (parent == null) return;
                    Path rel = base.relativize(parent);
                    if (rel.getNameCount() > 0) {
                        String first = rel.getName(0).toString();
                        if (first.equals(config.processedSubdir) || first.equals(config.errorSubdir) || first.equals(config.inProgressSubdir)) {
                            return;
                        }
                    }
                    out.add(p);
                });

        return out;
    }

    private Path moverPara(Path origem, Path destinoDir) throws IOException {
        if (origem == null || !Files.exists(origem)) return null;

        Path base = Paths.get(config.baseDir);
        Path rel = base.relativize(origem);
        Path destino = destinoDir.resolve(rel);

        Path destinoParent = destino.getParent();
        if (destinoParent != null) Files.createDirectories(destinoParent);

        // retry simples (Windows pode segurar handle)
        for (int i = 0; i < 3; i++) {
            try {
                Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
                return destino;
            } catch (Exception e) {
                sleepSeconds(i + 1);
            }
        }

        throw new IOException("Falha ao mover arquivo: " + origem + " -> " + destino);
    }
}

