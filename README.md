# 👁️ Monitoramento 24h com IA — Caminho Feliz

Este projeto monitora uma pasta por novos arquivos `.mp4`, extrai frames, filtra qualidade e similaridade via embeddings, descreve frames com IA, detecta eventos com prompt do arquivo de configuração, envia alerta no WhatsApp e recorta/enviar `time_clip` para o sistema **Arquivo Vivo** via socket.
Representa a etapa do **“caminho feliz”** na jornada de desenvolvimento de um sistema de monitoramento de câmeras com Inteligência Artificial.

## 🎯 Objetivo

Demonstrar, de forma prática e funcional, um fluxo onde:

- As imagens são capturadas de câmeras
- O vídeo é processado
- A Inteligência Artificial analisa os frames
- Eventos relevantes podem ser identificados

Tudo funcionando em um cenário ideal, sem falhas — ou seja, o famoso **“caminho feliz”**.

> ⚠️ Importante:  
> Este projeto cobre apenas cerca de **20% da complexidade real** de um sistema completo.  
> Os outros 80% envolvem tratamento de erros, integrações, performance e cenários reais.

---

## 🧠 O que este projeto faz

- Captura streams de vídeo de câmeras
- Converte streams em arquivos processáveis
- Processa frames de vídeo
- Permite integração com IA para análise de imagens
- Estrutura base para evolução de um sistema completo de monitoramento inteligente

---

## 🛠️ Tecnologias utilizadas - Pré-Requisitos

- **Java** (linguagem principal)
- Processamento de vídeo via **FFmpeg**
- Captura de streams via **Stream2MP4**
- Vetorização de imagens com **CLIP (OpenAI)**

---

## 🛠️ Clip-Service (Vetorização com IA)

Serviço local responsável por gerar embeddings de imagem utilizando o modelo:
openai/clip-vit-base-patch16
Esse serviço é essencial para análise inteligente das imagens.

---

## 🛠️ FFmpeg

Responsável pelo processamento de vídeo.
Download: https://ffmpeg.org/download.html
Após instalar, adicionar ao PATH
Verificar:
ffmpeg -version

## 🛠️ Stream2MP4

Ferramenta utilizada para:

Capturar streams RTSP
Converter para arquivos MP4

---

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




