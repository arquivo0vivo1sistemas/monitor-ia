package monitoria.av;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cópia empacotada do Socks5FileTransferClient do SOCKWHAPI.
 * Necessário porque a implementação original do SOCKWHAPI está no pacote default
 * e classes em pacotes nomeados não conseguem importá-la.
 */
public class Socks5FileTransferClient {

    private static final byte SOCKS5_VERSION = 0x05;
    private static final byte AUTH_METHOD_USERNAME_PASSWORD = 0x02;
    private static final byte AUTH_USERNAME_PASSWORD_VERSION = 0x01;
    private static final byte AUTH_SUCCESS = 0x00;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ADDR_TYPE_IPV4 = 0x01;
    private static final byte ADDR_TYPE_DOMAIN = 0x03;
    private static final byte ADDR_TYPE_IPV6 = 0x04;
    private static final byte REPLY_SUCCESS = 0x00;

    private static final byte STATUS_SUCESSO = 0x01;
    private static final byte STATUS_ERRO = 0x02;
    private static final byte STATUS_PROCESSANDO = 0x03;
    private static final byte STATUS_MENSAGEM = 0x04;  // seguido de 2 bytes length + UTF-8

    // Extensões suportadas quando usado o modo .ALL
    private static final String[] EXTENSOES_SUPORTADAS = {
            ".pdf", ".doc", ".docx",
            ".xls", ".xlsx",
            ".ppt", ".pptx",
            ".mp4",
            ".mp3", ".ogg"
    };

    private final String proxyHost;
    private final int proxyPort;
    private final String username;
    private final String password;
    private final String targetHost;
    private final int targetPort;
    private final boolean modoDireto;

    private final int connectionTimeout = 30000;
    private final int readTimeout = 300000; // 5 minutos

    /** Modo direto: conecta direto no servidor (sem proxy). */
    public Socks5FileTransferClient(String targetHost, int targetPort) {
        this.proxyHost = null;
        this.proxyPort = 0;
        this.username = null;
        this.password = null;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.modoDireto = true;
    }

    /** Modo proxy: conecta via SOCKS5 e depois no servidor. */
    public Socks5FileTransferClient(String proxyHost, int proxyPort, String username, String password,
                                    String targetHost, int targetPort) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username = username;
        this.password = password;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.modoDireto = false;
    }

    public void transferirArquivosDoDiretorio(String diretorioOrigem, String extensao, String chatId, String numeroWhats, int intervaloSegundosEntreVarreduras) throws IOException {
        File dirOrigem = new File(diretorioOrigem);
        if (!dirOrigem.exists() || !dirOrigem.isDirectory()) {
            throw new IOException("Diretório não encontrado: " + diretorioOrigem);
        }

        File dirTransmitido = new File(dirOrigem, "TRANSMITIDO");
        File dirComErro = new File(dirOrigem, "COM_ERRO");
        dirTransmitido.mkdirs();
        dirComErro.mkdirs();

        String extensaoLower = extensao != null ? extensao.toLowerCase() : "";
        boolean usarTodos = "all".equals(extensaoLower) || ".all".equals(extensaoLower);

        if (!usarTodos) {
            if (!extensaoLower.startsWith(".")) {
                extensaoLower = "." + extensaoLower;
            }
        }

        List<File> arquivos = listarArquivosParaTransmitir(dirOrigem, extensaoLower, usarTodos);
        if (arquivos.isEmpty() && intervaloSegundosEntreVarreduras <= 0) {
            return;
        }

        AtomicInteger sucesso = new AtomicInteger(0);
        AtomicInteger erro = new AtomicInteger(0);
        int totalProcessados = 0;

        while (true) {
            arquivos = listarArquivosParaTransmitir(dirOrigem, extensaoLower, usarTodos);
            if (arquivos.isEmpty()) {
                if (intervaloSegundosEntreVarreduras <= 0) break;
                sleepSeconds(intervaloSegundosEntreVarreduras);
                continue;
            }

            totalProcessados += arquivos.size();

            for (File arquivo : arquivos) {
                try {
                    boolean resultado = transferirArquivo(arquivo, chatId, numeroWhats);

                    if (resultado) {
                        boolean movido = moverArquivoParaDestino(arquivo, dirTransmitido);
                        if (movido) sucesso.incrementAndGet();
                        else erro.incrementAndGet();
                    } else {
                        boolean movido = moverArquivoParaDestino(arquivo, dirComErro);
                        if (movido) {
                            // ok
                        }
                        erro.incrementAndGet();
                    }
                } catch (Exception e) {
                    boolean movido = moverArquivoParaDestino(arquivo, dirComErro);
                    if (!movido) {
                        // ignore
                    }
                    erro.incrementAndGet();
                }
            }
        }

        // Mensagens finais omitidas no MVP.
    }

    private static void sleepSeconds(int seconds) throws IOException {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido", e);
        }
    }

    private static List<File> listarArquivosParaTransmitir(File dirOrigem, String extensaoLower, boolean usarTodos) {
        List<File> arquivos = new ArrayList<>();
        File[] files = dirOrigem.listFiles();
        if (files == null) return arquivos;

        for (File file : files) {
            if (!file.isFile()) continue;
            String nome = file.getName().toLowerCase();
            if (usarTodos) {
                for (String ext : EXTENSOES_SUPORTADAS) {
                    if (nome.endsWith(ext)) {
                        arquivos.add(file);
                        break;
                    }
                }
            } else {
                if (nome.endsWith(extensaoLower)) {
                    arquivos.add(file);
                }
            }
        }
        return arquivos;
    }

    private static boolean moverArquivoParaDestino(File arquivo, File dirDestino) {
        Path origem = arquivo.toPath();
        Path destino = Paths.get(dirDestino.getAbsolutePath(), arquivo.getName());
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            try {
                if (tentativa > 0) Thread.sleep(300L * tentativa);
                Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) {
                if (tentativa == 2) {
                    try {
                        Files.copy(origem, destino, StandardCopyOption.REPLACE_EXISTING);
                        Files.delete(origem);
                        return true;
                    } catch (Exception ignored) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private boolean transferirArquivo(File arquivo, String chatId, String numeroWhats) throws IOException {
        Socket socket = null;
        FileInputStream fileIn = null;
        try {
            socket = modoDireto ? connectDirect() : connectThroughProxy();
            socket.setSoTimeout(30 * 60 * 1000);
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            fileIn = new FileInputStream(arquivo);

            String cid = chatId != null ? chatId : "";
            String num = numeroWhats != null ? numeroWhats : "";

            // 1. Nome do arquivo, chatId e numeroWhats (cada um termina com \n)
            byte[] fileNameBytes = arquivo.getName().getBytes(StandardCharsets.UTF_8);
            out.write(fileNameBytes);
            out.write('\n');
            out.write(cid.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.write(num.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();

            // 2. Tamanho (8 bytes)
            long fileSize = arquivo.length();
            byte[] sizeBytes = new byte[8];
            long size = fileSize;
            for (int i = 7; i >= 0; i--) {
                sizeBytes[i] = (byte) (size & 0xFF);
                size >>= 8;
            }
            out.write(sizeBytes);
            out.flush();

            // 3. Conteúdo
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

            byte status = aguardarRespostaProcessamento(in);
            return status == STATUS_SUCESSO;
        } finally {
            if (fileIn != null) {
                try { fileIn.close(); } catch (IOException ignored) {}
            }
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private byte aguardarRespostaProcessamento(InputStream in) throws IOException {
        long ultimaAtualizacao = System.currentTimeMillis();
        int pontos = 0;
        String ultimaMensagem = "Processando";

        while (true) {
            if (in.available() > 0) {
                byte status = (byte) in.read();
                if (status == STATUS_MENSAGEM) {
                    int len = (in.read() & 0xFF) << 8 | (in.read() & 0xFF);
                    if (len > 0 && len <= 65535) {
                        byte[] buf = new byte[len];
                        int off = 0;
                        while (off < len) {
                            int n = in.read(buf, off, len - off);
                            if (n <= 0) break;
                            off += n;
                        }
                        if (off == len) ultimaMensagem = new String(buf, 0, off, StandardCharsets.UTF_8);
                    }
                    pontos = (pontos + 1) % 4;
                    ultimaAtualizacao = System.currentTimeMillis();
                    continue;
                } else if (status == STATUS_PROCESSANDO) {
                    pontos = (pontos + 1) % 4;
                    ultimaAtualizacao = System.currentTimeMillis();
                    continue;
                } else if (status == STATUS_SUCESSO) {
                    // Ler metadados opcionais: 2 bytes length + UTF-8
                    int len = (in.read() & 0xFF) << 8 | (in.read() & 0xFF);
                    if (len > 0 && len <= 65535) {
                        byte[] buf = new byte[len];
                        int off = 0;
                        while (off < len) {
                            int n = in.read(buf, off, len - off);
                            if (n <= 0) break;
                            off += n;
                        }
                    }
                    return status;
                } else if (status == STATUS_ERRO) {
                    return status;
                }
            }

            if (System.currentTimeMillis() - ultimaAtualizacao > 300000) {
                throw new IOException("Timeout aguardando resposta do servidor");
            }
            if (System.currentTimeMillis() - ultimaAtualizacao > 2000) {
                pontos = (pontos + 1) % 4;
                ultimaAtualizacao = System.currentTimeMillis();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrompido", e);
            }
        }
    }

    private Socket connectDirect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(targetHost, targetPort), connectionTimeout);
        socket.setSoTimeout(readTimeout);
        return socket;
    }

    private Socket connectThroughProxy() throws IOException {
        Socket proxySocket = new Socket();
        proxySocket.connect(new InetSocketAddress(proxyHost, proxyPort), connectionTimeout);
        proxySocket.setSoTimeout(readTimeout);

        InputStream proxyIn = proxySocket.getInputStream();
        OutputStream proxyOut = proxySocket.getOutputStream();

        proxyOut.write(SOCKS5_VERSION);
        proxyOut.write(0x01);
        proxyOut.write(AUTH_METHOD_USERNAME_PASSWORD);
        proxyOut.flush();

        byte[] response = new byte[2];
        if (proxyIn.read(response) < 2 || response[0] != SOCKS5_VERSION || response[1] != AUTH_USERNAME_PASSWORD_VERSION) {
            // melhor esforço; manter comportamento básico
        }

        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        proxyOut.write(AUTH_USERNAME_PASSWORD_VERSION);
        proxyOut.write(usernameBytes.length);
        proxyOut.write(usernameBytes);
        proxyOut.write(passwordBytes.length);
        proxyOut.write(passwordBytes);
        proxyOut.flush();

        proxyOut.write(SOCKS5_VERSION);
        proxyOut.write(CMD_CONNECT);
        proxyOut.write(0x00);

        InetAddress addr = InetAddress.getByName(targetHost);
        byte addrType = addr instanceof java.net.Inet4Address ? ADDR_TYPE_IPV4 : ADDR_TYPE_IPV6;
        byte[] addrBytes = addr.getAddress();
        proxyOut.write(addrType);
        proxyOut.write(addrBytes);
        proxyOut.write((targetPort >> 8) & 0xFF);
        proxyOut.write(targetPort & 0xFF);
        proxyOut.flush();

        byte[] connResponse = new byte[4];
        if (proxyIn.read(connResponse) < 4 || connResponse[1] != REPLY_SUCCESS) {
            proxySocket.close();
            throw new IOException("Falha ao conectar via proxy SOCKS5");
        }

        // descartar bytes adicionais
        if (connResponse[3] == ADDR_TYPE_IPV4) proxyIn.read(new byte[6]);
        else if (connResponse[3] == ADDR_TYPE_IPV6) proxyIn.read(new byte[18]);

        return proxySocket;
    }
}

