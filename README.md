# Aura Sensor Logger v2

Aplicativo Android profissional para telemetria veicular em tempo real. Coleta dados de GNSS, IMU e sensores ambientais a 1 Hz, publicando via MQTT com fila offline resiliente e exportação CSV. Inclui Debug Dashboard responsivo para diagnóstico em campo.

## 🚀 Principais Funcionalidades

### Coleta de Dados
- **GNSS Raw Measurements**: Medições brutas por satélite (CN0, Doppler, AGC, SNR, multipath)
- **IMU Completo**: Acelerômetro, giroscópio, magnetômetro, rotation vector
- **World-Frame Acceleration**: Aceleração compensada pela orientação do tablet (longitudinal, lateral, vertical)
- **Vehicle Dynamics**: Detecção de impacto, inclinação, risco de tombamento, estabilidade
- **Barômetro**: Pressão atmosférica e altitude (quando disponível)
- **Métricas Agregadas**: CN0 min/max/avg, Doppler speed/sigma, jerk, shock level

### Telemetria
- **MQTT Dual**: Suporta broker local (LAN) e cloud simultâneos
- **Auto-Discovery**: Varredura automática de brokers na rede local
- **Offline Queue**: Fila JSONL persistente para dados não enviados
- **CSV Export**: Arquivo telemetry.csv com dados completos
- **Schema v11**: Payload JSON otimizado e retrocompatível

### Debug Dashboard
- **Layout Responsivo**: Grade 2 colunas (portrait) / 3 colunas (landscape)
- **6 Cards de Diagnóstico**:
  - System/Service (status do logger, data age)
  - GNSS/Positioning (qualidade de sinal, health, motion state)
  - Vehicle Dynamics (impacto, curvas, freadas, roll risk)
  - IMU Raw/Sensors (calibração, data rate)
  - Barometer/Environment (auto-oculta se sem dados)
  - Network/Upload (delivery status, sync)
- **Tema Escuro**: Otimizado para uso prolongado
- **Interpretações Coloridas**: Verde (OK), Amarelo (WARN), Vermelho (ALERTA)

## 📱 Requisitos

- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)
- **Android Studio**: Giraffe+ ou Gradle CLI
- **Build Tools**: 35.0.0
- **Hardware Testado**: Samsung Galaxy Tab S9 FE 5G (SM-X516B)

## ⚙️ Configuração

### 1. Clone o Repositório
git clone git@github.com:gabrielsapucaia/aura_detector.git
cd aura_detector

### 2. Configurar Credenciais
cp local.defaults.properties local.properties

Edite local.properties com suas credenciais MQTT.

### 3. Compilar e Instalar
./gradlew assembleDebug
./gradlew installDebug

## 🔧 Ferramentas

### Busca Automática de Broker
powershell -ExecutionPolicy Bypass -File .\tools\find-broker.ps1

## 📊 Uso

1. Conceda permissões (Localização, Sensores, Notificações)
2. Desative otimização de bateria
3. Configure operador e equipamento
4. Pressione INICIAR COLETA

### Debug Dashboard
Acesse via botão 🔧 Debug Dashboard na tela principal.

## 📁 Arquivos de Dados

Localização: /storage/emulated/0/Android/data/com.example.sensorlogger/files/telemetry/

- telemetry.csv: Dados completos em CSV
- pending_mqtt.jsonl: Fila offline
- aurasensor.log: Logs da aplicação

## 🏗️ Tecnologias

- Kotlin 1.9+ com Coroutines
- MQTT: Eclipse Paho
- Serialização: Kotlinx Serialization
- UI: Material Design 3
- Arquitetura: MVVM + StateFlow

## 📝 Versionamento

- **v2.0.0** (2025-10-26): Release inicial

## 📄 Licença

Projeto proprietário - Uso interno apenas.
