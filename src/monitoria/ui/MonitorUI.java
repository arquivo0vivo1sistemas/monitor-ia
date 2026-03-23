package monitoria.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UI simples com system tray + janela para acompanhar logs.
 */
public class MonitorUI implements LogSink {

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final JTextArea logArea;
    private final JFrame frame;
    private final JLabel statusLabel;

    public MonitorUI() {
        // Se rodar em modo headless, não cria UI.
        if (GraphicsEnvironment.isHeadless()) {
            frame = null;
            logArea = null;
            statusLabel = null;
            return;
        }

        frame = new JFrame("MONITOR_IA");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setSize(900, 650);
        frame.setLayout(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Status: iniciando...");
        top.add(statusLabel, BorderLayout.CENTER);

        JButton toggle = new JButton("Parar");
        toggle.addActionListener((ActionEvent e) -> {
            boolean next = !running.get();
            running.set(next);
            toggle.setText(next ? "Parar" : "Iniciar");
            log("Controle manual: " + (next ? "rodando" : "parado"));
        });
        top.add(toggle, BorderLayout.EAST);
        frame.add(top, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        frame.add(scroll, BorderLayout.CENTER);

        JButton clear = new JButton("Limpar log");
        clear.addActionListener(e -> logArea.setText(""));
        frame.add(clear, BorderLayout.SOUTH);

        // Tray
        initTrayIcon();

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // não encerramos processo: minimiza para bandeja
                minimizeToTray();
            }
        });

        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    public boolean isRunning() {
        return running.get();
    }

    private void initTrayIcon() {
        try {
            if (!SystemTray.isSupported()) return;
            SystemTray tray = SystemTray.getSystemTray();

            Image img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            TrayIcon trayIcon = new TrayIcon(img);
            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip("MONITOR_IA (rodando)");

            PopupMenu popup = new PopupMenu();
            MenuItem showHide = new MenuItem("Mostrar/ocultar");
            showHide.addActionListener(e -> toggleVisibility());
            popup.add(showHide);

            MenuItem quit = new MenuItem("Sair");
            quit.addActionListener(e -> {
                running.set(false);
                System.exit(0);
            });
            popup.add(quit);

            trayIcon.setPopupMenu(popup);
            tray.add(trayIcon);

        } catch (Exception e) {
            // Falha no tray: UI ainda funciona.
            System.err.println("Falha ao iniciar system tray: " + e.getMessage());
        }
    }

    private void toggleVisibility() {
        if (frame == null) return;
        SwingUtilities.invokeLater(() -> {
            boolean isVisible = frame.isVisible();
            if (isVisible) {
                minimizeToTray();
            } else {
                frame.setVisible(true);
                frame.toFront();
            }
        });
    }

    private void minimizeToTray() {
        if (frame == null) return;
        SwingUtilities.invokeLater(() -> frame.setVisible(false));
    }

    @Override
    public void log(String msg) {
        String safe = msg == null ? "" : msg;
        if (logArea == null) {
            System.out.println(safe);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            logArea.append(safe + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    @Override
    public void logError(String msg, Throwable t) {
        log("[ERRO] " + msg);
        if (t != null) {
            log("Detalhes: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void setStatus(String msg) {
        if (statusLabel == null) {
            if (msg != null) System.out.println(msg);
            return;
        }
        SwingUtilities.invokeLater(() -> statusLabel.setText("Status: " + (msg == null ? "" : msg)));
    }
}

