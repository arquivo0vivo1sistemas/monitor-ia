package monitoria.ui;

public interface LogSink {
    void log(String msg);
    void logError(String msg, Throwable t);
    void setStatus(String msg);
}

