# MONITOR_IA (MVP)

Este projeto monitora uma pasta por novos arquivos `.mp4`, extrai frames, filtra qualidade e similaridade via embeddings, descreve frames com IA, detecta eventos com prompt do arquivo de configuração, envia alerta no WhatsApp e recorta/enviar `time_clip` para o sistema **Arquivo Vivo** via socket.

## Requisitos
- Java 11
- Maven
- `ffmpeg` disponível no PATH (ou em `C:\ffmpeg\bin\ffmpeg.exe`).
- Endpoint local de embeddings (conforme o `SOCKWHAPI`), se você for usar `EmbeddingService`.

## Build
No diretório `MONITOR_IA`:
```sh
mvn -DskipTests package
```

O jar final deve ficar em `target/`.

## Configuração
Edite `monitor-config.sample.json` e renomeie para `monitor-config.json` (ou passe o caminho via `--config`).

Prompts principais (vindos do arquivo):
- `vision.DESCREVER_FRAME`
- `events.DETECCAO_DE_EVENTOS_E_ACAO`

## Execução
```sh
java -jar target/monitoria_ia-1.0.0.jar --config "C:\caminho\monitor-config.json"
```

