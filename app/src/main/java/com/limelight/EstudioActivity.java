package com.limelight;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrShared;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.limelight.binding.video.XrShared.*;

/**
 * Pop3D: o Quest como monitor VR remoto. O PC manda video HEVC, audio PCM,
 * texturas e a lista de camadas a desenhar; este app mantem a sessao OpenXR,
 * desenha as camadas (XrRenderer em modo remoto) e devolve cabeca, controles e
 * botoes a cada quadro. Ver PROTOCOLO.md.
 *
 * Protocolo (TCP 52301): [tipo u8][tamanho u32 BE][payload]. Descoberta por
 * UDP broadcast 52300 ("ESTUDIO3D?" -> "ESTUDIO3D!" + JSON) ou, por USB,
 * 127.0.0.1:52301 via "adb reverse".
 */
public class EstudioActivity extends Activity implements XrRenderer.RemoteListener,
        PerfOverlayListener {

    private static final String TAG = "Pop3D: ";

    private static final int PORTA_UDP = 52300;
    private static final int PORTA_TCP = 52301;

    // PC -> Quest
    private static final int MSG_VIDEO_CONFIG = 0x01;
    private static final int MSG_VIDEO_AU = 0x02;
    private static final int MSG_AUDIO_CONFIG = 0x03;
    private static final int MSG_AUDIO_PCM = 0x04;
    private static final int MSG_STATUS = 0x05;
    private static final int MSG_TEXTURA = 0x10;
    private static final int MSG_CAMADAS = 0x11;
    private static final int MSG_VIBRAR = 0x12;
    private static final int MSG_FUNDO = 0x13;
    private static final int MSG_SAIR = 0x14;
    // Quest -> PC
    private static final int MSG_COMMAND = 0x81;
    private static final int MSG_NEED_IDR = 0x82;
    private static final int MSG_ENTRADA = 0x83;
    private static final int MSG_OLA = 0x84;
    // 3: entende SAIR (o botao "Sair do VR" fecha o app) e acorda o Pop3D do PC pelo despertador
    private static final int VERSAO = 3;
    private static final int PORTA_DESPERTADOR = 52299;
    // Media de no maximo ~90 ENTRADA/s (balde: a 120 Hz saem 3 de cada 4 quadros)
    private static final long ENTRADA_INTERVALO_NS = 11_000_000L;
    // ENTRADA ainda na fila de envio acima disto: a rede esta atrasada, solta
    private static final int ENTRADA_MAX_PENDENTES = 4;
    private static final String TEX_LOCAL = "_local";

    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;
    // Mais que isto de audio na fila e o mais antigo e descartado
    private static final int AUDIO_MAX_AMOSTRAS = 48000 * 2 / 4;

    private PreferenceConfiguration prefs;
    private String glRenderer = "";
    private final Handler principal = new Handler(Looper.getMainLooper());

    private volatile boolean rodando;
    private volatile boolean conectado;
    private XrRenderer xr;
    private Thread threadRede;
    private WifiManager.MulticastLock multicastLock;

    // Escritas na rede saem por aqui para nunca bloquear quem as pede
    private final ExecutorService envios = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private OutputStream saida;
    private final Object travaSaida = new Object();

    // Video (so a thread de rede mexe nestes)
    private MediaCodecDecoderRenderer decoder;
    private boolean decoderIniciado;
    private int decLargura, decAltura;
    private int numeroQuadro;
    private boolean aguardandoIdr = true;
    private long ultimoPedidoIdrMs;
    private byte[] bufPic = new byte[1 << 20];

    // Audio
    private AndroidAudioRenderer audio;
    private int audioTaxa;
    private Thread threadAudio;
    private final LinkedBlockingQueue<short[]> filaAudio = new LinkedBlockingQueue<>();
    private final AtomicInteger amostrasNaFila = new AtomicInteger();

    // Estatisticas de 1 s
    private final AtomicInteger estQuadros = new AtomicInteger();
    private final AtomicLong estBytes = new AtomicLong();
    private final AtomicInteger estAudio = new AtomicInteger();
    private final AtomicInteger estEntradas = new AtomicInteger();
    private final AtomicInteger estCamadasRecebidas = new AtomicInteger();
    private final AtomicInteger estTexturas = new AtomicInteger();
    private final AtomicInteger entradasPendentes = new AtomicInteger();
    private long proximaEntradaNs;
    // O recentrar chega num quadro so; se ele nao puder sair (limite de taxa),
    // fica guardado para o proximo envio
    private boolean recentrouPendente;
    // Painel local ("Procurando o seu PC"): posto de novo na frente da cabeca
    // quando a ligacao cai ou o usuario recentra
    private boolean precisaPainel = true;
    private volatile boolean recebeuCamadas;
    private ByteBuffer painelPixels;
    private int estUltIn, estUltOut;
    private long estUltMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new View(this));

        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        glRenderer = glPrefs.glRenderer;
        MediaCodecHelper.initialize(this, glRenderer);

        prefs = PreferenceConfiguration.readPreferences(this);
        prefs.enableVrMode = true;
        // O PC desenha tudo; o app so mostra camadas. Nada de maos nem modelo de profundidade.
        prefs.vrDepthMode = XrShared.DEPTH_MODE_OFF;
        prefs.vrHandTracking = false;
        prefs.vrAmbilight = false;
        prefs.vrRoomLight = false;
        prefs.videoFormat = PreferenceConfiguration.FormatOption.FORCE_HEVC;
        prefs.framePacing = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        prefs.width = 3840;
        prefs.height = 1080;
        prefs.fps = 30;
        prefs.enablePerfOverlay = getIntent().getBooleanExtra("estatisticas", false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (xr == null) {
            // A sessao sobe ja, antes de qualquer ligacao; o decoder se liga a ela
            // quando chegar video. Ela se recria sozinha se o sistema a encerrar.
            xr = new XrRenderer();
            montarPainelLocal();
            xr.startRemote(this, prefs.width, prefs.height, prefs, this);
        }
        if (threadRede == null) {
            rodando = true;
            threadRede = new Thread(new Runnable() {
                @Override
                public void run() {
                    cicloRede();
                }
            }, "Estudio - Rede");
            threadRede.start();
            agendarEstatisticas();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rodando = false;
        principal.removeCallbacksAndMessages(null);
        fecharSocket();
        if (threadRede != null) {
            threadRede.interrupt();
            try {
                threadRede.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        envios.shutdownNow();
        pararAudio();
        destruirDecoder();
        if (xr != null) {
            xr.prepareForStop();
            xr.cleanup();
            xr = null;
        }
        liberarMulticast();
    }

    // ------------------------------------------------------------------
    // Ligacao

    private void cicloRede() {
        while (rodando) {
            Socket s = descobrirEConectar();
            if (s == null) {
                continue;
            }
            LimeLog.info(TAG + "ligado a " + s.getRemoteSocketAddress());
            socket = s;
            try {
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                synchronized (travaSaida) {
                    saida = s.getOutputStream();
                }
                aguardandoIdr = true;
                enviarOla();
                // O PC ja forca um IDR ao aceitar, mas pedir nao custa
                enviarVazio(MSG_NEED_IDR);
                aoConectar();
                lerMensagens(s);
            } catch (IOException e) {
                if (rodando) {
                    LimeLog.info(TAG + "ligacao terminada: " + e);
                }
            } catch (RuntimeException e) {
                LimeLog.severe(TAG + "erro a tratar mensagens: " + e);
                e.printStackTrace();
            } finally {
                aoDesconectar();
                fecharSocket();
            }
            if (rodando) {
                LimeLog.info(TAG + "desligado, a tentar de novo");
                pausa(1000);
            }
        }
    }

    private void pausa(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fecharSocket() {
        Socket s = socket;
        socket = null;
        synchronized (travaSaida) {
            saida = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // Tenta o USB (adb reverse) e depois o broadcast, uma vez por segundo
    private Socket descobrirEConectar() {
        long inicio = System.currentTimeMillis();
        Socket s = null;

        try {
            Socket usb = new Socket();
            usb.connect(new InetSocketAddress("127.0.0.1", PORTA_TCP), 400);
            s = usb;
        } catch (IOException ignored) {
        }

        if (s == null && rodando) {
            adquirirMulticast();
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                ds.setSoTimeout(600);
                byte[] msg = "ESTUDIO3D?".getBytes(StandardCharsets.US_ASCII);
                ds.send(new DatagramPacket(msg, msg.length,
                        InetAddress.getByName("255.255.255.255"), PORTA_UDP));
                byte[] buf = new byte[1024];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                String txt = new String(buf, 0, resp.getLength(), StandardCharsets.UTF_8);
                if (txt.startsWith("ESTUDIO3D!")) {
                    int porta = PORTA_TCP;
                    String nome = "?";
                    try {
                        JSONObject j = new JSONObject(txt.substring("ESTUDIO3D!".length()));
                        porta = j.optInt("porta", PORTA_TCP);
                        nome = j.optString("nome", "?");
                    } catch (Exception ignored) {
                    }
                    LimeLog.info(TAG + "PC encontrado: " + nome + " " + resp.getAddress());
                    Socket wifi = new Socket();
                    wifi.connect(new InetSocketAddress(resp.getAddress(), porta), 1500);
                    s = wifi;
                }
            } catch (IOException ignored) {
                // sem resposta ou sem rede: volta a tentar
            } finally {
                if (ds != null) {
                    ds.close();
                }
            }
        }

        if (s == null) {
            long resto = 1000 - (System.currentTimeMillis() - inicio);
            if (resto > 0) {
                pausa(resto);
            }
        } else {
            liberarMulticast();
        }
        return s;
    }

    private void adquirirMulticast() {
        if (multicastLock != null) {
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("pop3d");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException e) {
            LimeLog.warning(TAG + "sem MulticastLock: " + e);
            multicastLock = null;
        }
    }

    private void liberarMulticast() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (RuntimeException ignored) {
        }
        multicastLock = null;
    }

    // ------------------------------------------------------------------
    // Mensagens

    private void lerMensagens(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        byte[] cab = new byte[5];
        while (rodando) {
            in.readFully(cab);
            int tipo = cab[0] & 0xFF;
            int tam = ((cab[1] & 0xFF) << 24) | ((cab[2] & 0xFF) << 16)
                    | ((cab[3] & 0xFF) << 8) | (cab[4] & 0xFF);
            if (tam < 0 || tam > MAX_PAYLOAD) {
                throw new IOException("mensagem invalida: tipo " + tipo + " tamanho " + tam);
            }
            byte[] p = new byte[tam];
            in.readFully(p);
            estBytes.addAndGet(tam + 5);

            switch (tipo) {
                case MSG_VIDEO_CONFIG:
                    tratarVideoConfig(p);
                    break;
                case MSG_VIDEO_AU:
                    tratarVideoAu(p);
                    break;
                case MSG_AUDIO_CONFIG:
                    tratarAudioConfig(p);
                    break;
                case MSG_AUDIO_PCM:
                    tratarAudioPcm(p);
                    break;
                case MSG_TEXTURA:
                    tratarTextura(p);
                    break;
                case MSG_CAMADAS:
                    tratarCamadas(p);
                    break;
                case MSG_VIBRAR:
                    tratarVibrar(p);
                    break;
                case MSG_FUNDO:
                    tratarFundo(p);
                    break;
                case MSG_STATUS:
                    break;
                case MSG_SAIR:
                    // "Sair do VR" no PC: fecha o app e volta para a tela inicial da Meta
                    LimeLog.info(TAG + "PC pediu para sair");
                    principal.post(new Runnable() {
                        @Override
                        public void run() {
                            finishAndRemoveTask();
                        }
                    });
                    break;
                default:
                    LimeLog.warning(TAG + "tipo de mensagem desconhecido " + tipo);
            }
        }
    }

    private void enviar(final int tipo, final byte[] payload) {
        try {
            envios.execute(new Runnable() {
                @Override
                public void run() {
                    escrever(tipo, payload);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    // Na thread de envio: uma mensagem inteira por vez
    private void escrever(int tipo, byte[] payload) {
        synchronized (travaSaida) {
            if (saida == null) {
                return;
            }
            try {
                byte[] cab = new byte[5];
                cab[0] = (byte) tipo;
                cab[1] = (byte) (payload.length >>> 24);
                cab[2] = (byte) (payload.length >>> 16);
                cab[3] = (byte) (payload.length >>> 8);
                cab[4] = (byte) payload.length;
                saida.write(cab);
                saida.write(payload);
                saida.flush();
            } catch (IOException e) {
                LimeLog.warning(TAG + "falha ao enviar: " + e);
            }
        }
    }

    private void enviarVazio(int tipo) {
        enviar(tipo, new byte[0]);
    }

    private void enviarComando(String cmd) {
        try {
            JSONObject j = new JSONObject();
            j.put("cmd", cmd);
            enviar(MSG_COMMAND, j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LimeLog.warning(TAG + "comando " + cmd + ": " + e);
        }
    }

    private void pedirIdr() {
        aguardandoIdr = true;
        long agora = System.currentTimeMillis();
        if (agora - ultimoPedidoIdrMs >= 300) {
            ultimoPedidoIdrMs = agora;
            enviarVazio(MSG_NEED_IDR);
        }
    }

    // ------------------------------------------------------------------
    // Video

    private void tratarVideoConfig(byte[] p) {
        try {
            JSONObject j = new JSONObject(new String(p, StandardCharsets.UTF_8));
            int l = j.optInt("largura", 3840);
            int a = j.optInt("altura", 1080);
            int fps = j.optInt("fps", 30);
            String codec = j.optString("codec", "hevc");
            LimeLog.info(TAG + "VIDEO_CONFIG " + codec + " " + l + "x" + a + " @" + fps);
            if (!codec.equals("hevc")) {
                LimeLog.severe(TAG + "so hevc e suportado, recebido " + codec);
                return;
            }
            garantirDecoder(l, a, fps);
            aguardandoIdr = true;
        } catch (Exception e) {
            LimeLog.severe(TAG + "VIDEO_CONFIG invalido: " + e);
        }
    }

    private void garantirDecoder(int largura, int altura, int fps) {
        if (decoder != null && decoderIniciado && decLargura == largura && decAltura == altura) {
            return;
        }
        destruirDecoder();

        XrRenderer sessao = xr;
        if (sessao == null) {
            return;
        }
        // A textura "video" passa a ter o tamanho do quadro decodificado
        sessao.remoteSetVideoSize(largura, altura);
        if (!sessao.waitForSurface(8000)) {
            LimeLog.severe(TAG + "sessao XR sem superficie, decoder nao criado");
            return;
        }
        prefs.width = largura;
        prefs.height = altura;
        prefs.fps = fps;
        MediaCodecDecoderRenderer d = new MediaCodecDecoderRenderer(this, prefs,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        LimeLog.severe(TAG + "decoder falhou: " + e);
                    }
                },
                0, false, false, glRenderer, this);
        d.setStandalone(true);
        d.setExternalXrRenderer(sessao);
        int r = d.setup(MoonBridge.VIDEO_FORMAT_H265, largura, altura, fps);
        decoder = d;
        if (r != 0) {
            LimeLog.severe(TAG + "decoder.setup falhou: " + r);
            destruirDecoder();
            return;
        }
        d.start();
        decoderIniciado = true;
        decLargura = largura;
        decAltura = altura;
        estUltIn = estUltOut = 0;
        LimeLog.info(TAG + "decoder configurado " + largura + "x" + altura + " @" + fps
                + " (monitor remoto)");
    }

    private void destruirDecoder() {
        MediaCodecDecoderRenderer d = decoder;
        decoder = null;
        if (d == null) {
            return;
        }
        d.stopXrRenderer();
        d.prepareForStop();
        try {
            if (decoderIniciado) {
                d.stop();
            }
            d.cleanup();
        } catch (RuntimeException e) {
            LimeLog.warning(TAG + "erro a destruir decoder: " + e);
        }
        decoderIniciado = false;
    }

    private static int tipoNal(byte b) {
        return (b >> 1) & 0x3F;
    }

    private void tratarVideoAu(byte[] p) {
        if (p.length < 9 || decoder == null || !decoderIniciado) {
            return;
        }
        boolean chave = (p[8] & 1) != 0;
        estQuadros.incrementAndGet();
        if (aguardandoIdr) {
            if (!chave) {
                return;
            }
            aguardandoIdr = false;
        }

        long recebidoUs = System.nanoTime() / 1000;
        int frameType = chave ? MoonBridge.FRAME_TYPE_IDR : MoonBridge.FRAME_TYPE_PFRAME;
        int n = ++numeroQuadro;
        MediaCodecDecoderRenderer d = decoder;

        // Acha os start codes (00 00 01, com o 00 anterior se houver)
        int fim = p.length;
        int[] codigos = new int[32];
        int cnt = 0;
        for (int k = 9; k + 3 < fim; k++) {
            if (p[k] == 0 && p[k + 1] == 0 && p[k + 2] == 1) {
                if (cnt == codigos.length) {
                    codigos = java.util.Arrays.copyOf(codigos, cnt * 2);
                }
                codigos[cnt++] = k;
                k += 2;
            }
        }

        int picLen = 0;
        int ret = MoonBridge.DR_OK;
        for (int j = 0; j < cnt; j++) {
            int k = codigos[j];
            int nalIni = (k > 9 && p[k - 1] == 0) ? k - 1 : k;
            int proxK = j + 1 < cnt ? codigos[j + 1] : fim;
            int nalFim = (j + 1 < cnt && proxK > k + 3 && p[proxK - 1] == 0) ? proxK - 1 : proxK;
            int len = nalFim - nalIni;
            int tipo = tipoNal(p[k + 3]);
            if (tipo == 32 || tipo == 33 || tipo == 34) {
                byte[] nal = new byte[len];
                System.arraycopy(p, nalIni, nal, 0, len);
                int dec = tipo == 32 ? MoonBridge.BUFFER_TYPE_VPS
                        : tipo == 33 ? MoonBridge.BUFFER_TYPE_SPS
                        : MoonBridge.BUFFER_TYPE_PPS;
                int r = d.submitDecodeUnit(nal, len, dec, n, frameType, (char) 0,
                        recebidoUs, System.nanoTime() / 1000);
                if (r != MoonBridge.DR_OK) {
                    ret = r;
                }
            } else {
                if (picLen + len > bufPic.length) {
                    bufPic = java.util.Arrays.copyOf(bufPic, Math.max(bufPic.length * 2, picLen + len));
                }
                System.arraycopy(p, nalIni, bufPic, picLen, len);
                picLen += len;
            }
        }

        if (picLen > 0) {
            int r = d.submitDecodeUnit(bufPic, picLen, MoonBridge.BUFFER_TYPE_PICDATA, n,
                    frameType, (char) 0, recebidoUs, System.nanoTime() / 1000);
            if (r != MoonBridge.DR_OK) {
                ret = r;
            }
        }
        if (ret == MoonBridge.DR_NEED_IDR) {
            pedirIdr();
        }
    }

    // ------------------------------------------------------------------
    // Audio

    private void tratarAudioConfig(byte[] p) {
        try {
            JSONObject j = new JSONObject(new String(p, StandardCharsets.UTF_8));
            int taxa = j.optInt("taxa", 48000);
            int canais = j.optInt("canais", 2);
            LimeLog.info(TAG + "AUDIO_CONFIG " + taxa + " Hz, " + canais + " canais");
            if (canais != 2) {
                LimeLog.severe(TAG + "so estereo e suportado");
                return;
            }
            if (audio != null && audioTaxa == taxa) {
                return;
            }
            pararAudio();
            AndroidAudioRenderer a = new AndroidAudioRenderer(this, false);
            a.setStandalone(true);
            // 960 amostras (20 ms) por bloco: buffer de 40 ms, mais folgado que o do Moonlight
            int r = a.setup(MoonBridge.AUDIO_CONFIGURATION_STEREO, taxa, 960);
            if (r != 0) {
                LimeLog.severe(TAG + "audio.setup falhou: " + r);
                return;
            }
            a.start();
            audio = a;
            audioTaxa = taxa;
            threadAudio = new Thread(new Runnable() {
                @Override
                public void run() {
                    cicloAudio();
                }
            }, "Estudio - Audio");
            threadAudio.start();
        } catch (Exception e) {
            LimeLog.severe(TAG + "AUDIO_CONFIG invalido: " + e);
        }
    }

    private void cicloAudio() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AndroidAudioRenderer a = audio;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                short[] blocos = filaAudio.take();
                amostrasNaFila.addAndGet(-blocos.length);
                a.playDecodedAudio(blocos);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LimeLog.warning(TAG + "audio: " + e);
            }
        }
    }

    private void tratarAudioPcm(byte[] p) {
        if (audio == null || p.length <= 8) {
            return;
        }
        int n = (p.length - 8) / 2;
        short[] amostras = new short[n];
        ByteBuffer.wrap(p, 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(amostras);
        estAudio.addAndGet(n);
        filaAudio.add(amostras);
        int total = amostrasNaFila.addAndGet(n);
        // Rede atrasada: descarta o mais antigo em vez de crescer a latencia
        while (total > AUDIO_MAX_AMOSTRAS) {
            short[] velho = filaAudio.poll();
            if (velho == null) {
                break;
            }
            total = amostrasNaFila.addAndGet(-velho.length);
        }
    }

    private void pararAudio() {
        Thread t = threadAudio;
        threadAudio = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        filaAudio.clear();
        amostrasNaFila.set(0);
        AndroidAudioRenderer a = audio;
        audio = null;
        if (a != null) {
            try {
                a.stop();
                a.cleanup();
            } catch (RuntimeException e) {
                LimeLog.warning(TAG + "erro a parar audio: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Estatisticas em logcat

    private void agendarEstatisticas() {
        estUltMs = System.currentTimeMillis();
        principal.postDelayed(new Runnable() {
            @Override
            public void run() {
                long agora = System.currentTimeMillis();
                double dt = Math.max(1, agora - estUltMs) / 1000.0;
                estUltMs = agora;
                MediaCodecDecoderRenderer d = decoder;
                int in = d != null ? d.getNumFramesIn() : 0;
                int out = d != null ? d.getNumFramesOut() : 0;
                int q = estQuadros.getAndSet(0);
                long b = estBytes.getAndSet(0);
                int au = estAudio.getAndSet(0);
                int ent = estEntradas.getAndSet(0);
                int cam = estCamadasRecebidas.getAndSet(0);
                int tex = estTexturas.getAndSet(0);
                LimeLog.info(TAG + String.format(Locale.US,
                        "rede %.1f quadros/s %.1f Mbps | decoder entrada %.1f/s saida %.1f/s"
                                + " (%dx%d) | audio %.0f amostras/s | ENTRADA enviadas %.0f/s"
                                + " | CAMADAS recebidas %.0f/s | texturas recebidas %d%s",
                        q / dt, b * 8 / dt / 1e6, (in - estUltIn) / dt, (out - estUltOut) / dt,
                        decLargura, decAltura, au / dt, ent / dt, cam / dt, tex,
                        conectado ? "" : " | sem ligacao"));
                estUltIn = in;
                estUltOut = out;
                if (rodando) {
                    principal.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    // ------------------------------------------------------------------
    // Camadas, texturas, vibracao, fundo (PC -> Quest)

    private void tratarTextura(byte[] p) {
        XrRenderer sessao = xr;
        if (sessao == null || p.length < 7) {
            return;
        }
        int n = p[0] & 0xFF;
        if (p.length < 1 + n + 5) {
            LimeLog.warning(TAG + "TEXTURA truncada");
            return;
        }
        String id = new String(p, 1, n, StandardCharsets.UTF_8);
        int o = 1 + n;
        int w = ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF);
        int h = ((p[o + 2] & 0xFF) << 8) | (p[o + 3] & 0xFF);
        int formato = p[o + 4] & 0xFF;
        if (formato != 1) {
            LimeLog.warning(TAG + "TEXTURA " + id + " com formato " + formato + " desconhecido");
            return;
        }
        if (sessao.remoteReceiveTexture(id, w, h, p, o + 5, p.length - o - 5)) {
            estTexturas.incrementAndGet();
            LimeLog.info(TAG + "textura '" + id + "' " + w + "x" + h + " recebida ("
                    + (p.length - o - 5) / 1024 + " KB comprimidos)");
        }
    }

    private static float num(JSONArray a, int i, float def) {
        return a != null && i < a.length() ? (float) a.optDouble(i, def) : def;
    }

    private void tratarCamadas(byte[] p) {
        XrRenderer sessao = xr;
        if (sessao == null) {
            return;
        }
        try {
            JSONArray lista = new JSONObject(new String(p, StandardCharsets.UTF_8))
                    .optJSONArray("camadas");
            int total = lista != null ? Math.min(lista.length(), 48) : 0;
            String[] ids = new String[total];
            float[] dados = new float[total * RL_STRIDE];
            int n = 0;
            for (int i = 0; i < total; i++) {
                JSONObject c = lista.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String tipo = c.optString("tipo", "");
                int o = n * RL_STRIDE;
                if (tipo.equals("quad")) {
                    dados[o + RL_KIND] = RL_KIND_QUAD;
                } else if (tipo.equals("cilindro")) {
                    dados[o + RL_KIND] = RL_KIND_CYLINDER;
                } else if (tipo.equals("laser")) {
                    dados[o + RL_KIND] = RL_KIND_LASER;
                } else {
                    continue;
                }
                if (tipo.equals("laser")) {
                    dados[o + RL_HAND] = "dir".equals(c.optString("mao")) ? 1 : 0;
                    JSONArray pt = c.optJSONArray("ponto");
                    for (int k = 0; k < 3; k++) {
                        dados[o + RL_POINT + k] = num(pt, k, 0f);
                    }
                    ids[n] = null;
                    n++;
                    continue;
                }
                ids[n] = c.optString("tex", "");
                String olho = c.optString("olho", "ambos");
                dados[o + RL_EYE] = olho.equals("esq") ? 1 : (olho.equals("dir") ? 2 : 0);
                // alpha: false/ausente = opaca, true = nao pre-multiplicada
                dados[o + RL_ALPHA] = c.optBoolean("alpha", false) ? 1 : 0;
                JSONArray rect = c.optJSONArray("rect");
                for (int k = 0; k < 4; k++) {
                    dados[o + RL_RECT + k] = num(rect, k, 0f);
                }
                JSONArray pos = c.optJSONArray("pos");
                for (int k = 0; k < 3; k++) {
                    dados[o + RL_POS + k] = num(pos, k, 0f);
                }
                JSONArray rot = c.optJSONArray("rot");
                for (int k = 0; k < 4; k++) {
                    dados[o + RL_ROT + k] = num(rot, k, k == 3 ? 1f : 0f);
                }
                if (tipo.equals("quad")) {
                    JSONArray tam = c.optJSONArray("tam");
                    dados[o + RL_SIZE] = num(tam, 0, 1f);
                    dados[o + RL_SIZE + 1] = num(tam, 1, 1f);
                } else {
                    dados[o + RL_RADIUS] = (float) c.optDouble("raio", 1.0);
                    dados[o + RL_ANGLE] = (float) c.optDouble("angulo", 1.0);
                    dados[o + RL_ASPECT] = (float) c.optDouble("aspecto", 1.0);
                }
                n++;
            }
            recebeuCamadas = true;
            sessao.remoteSetLayers(ids, dados, n);
            estCamadasRecebidas.incrementAndGet();
        } catch (Exception e) {
            LimeLog.warning(TAG + "CAMADAS invalido: " + e);
        }
    }

    private void tratarVibrar(byte[] p) {
        XrRenderer sessao = xr;
        if (sessao == null) {
            return;
        }
        try {
            JSONObject j = new JSONObject(new String(p, StandardCharsets.UTF_8));
            int mao = "dir".equals(j.optString("mao")) ? 1 : 0;
            sessao.remoteHaptic(mao, (float) j.optDouble("forca", 0.5), j.optLong("ms", 30));
        } catch (Exception e) {
            LimeLog.warning(TAG + "VIBRAR invalido: " + e);
        }
    }

    private void tratarFundo(byte[] p) {
        XrRenderer sessao = xr;
        if (sessao == null) {
            return;
        }
        try {
            JSONObject j = new JSONObject(new String(p, StandardCharsets.UTF_8));
            boolean passthrough = "passthrough".equals(j.optString("fundo", "preto"));
            sessao.remoteSetPassthrough(passthrough);
            LimeLog.info(TAG + "FUNDO " + (passthrough ? "passthrough" : "preto"));
        } catch (Exception e) {
            LimeLog.warning(TAG + "FUNDO invalido: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Quest -> PC: OLA e ENTRADA

    private void enviarOla() {
        try {
            JSONObject j = new JSONObject();
            j.put("versao", VERSAO);
            j.put("modelo", Build.MODEL);
            j.put("hz", Math.round(getWindowManager().getDefaultDisplay().getRefreshRate()));
            XrRenderer sessao = xr;
            j.put("cilindro", sessao != null && sessao.isCylinderSupported());
            enviar(MSG_OLA, j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LimeLog.warning(TAG + "OLA: " + e);
        }
    }

    private static void arr(StringBuilder sb, float[] v, int off, int n) {
        sb.append('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.4f", v[off + i]));
        }
        sb.append(']');
    }

    private static void mao(StringBuilder sb, float[] in, int base) {
        boolean valida = in[base] != 0;
        sb.append('{');
        if (valida) {
            sb.append("\"pos\":");
            arr(sb, in, base + 1, 3);
            sb.append(",\"rot\":");
            arr(sb, in, base + 4, 4);
        } else {
            sb.append("\"pos\":null,\"rot\":null");
        }
        sb.append(",\"gatilho\":").append(in[base + 8] > 0.5f);
        sb.append(",\"aperto\":").append(in[base + 9] > 0.5f);
        sb.append(",\"a\":").append(in[base + 10] > 0.5f);
        sb.append(",\"b\":").append(in[base + 11] > 0.5f);
        sb.append(",\"analogico\":");
        arr(sb, in, base + 12, 2);
        sb.append('}');
    }

    private void enviarEntrada(float[] in) {
        long agora = System.nanoTime();
        if (in[RIN_RECENTERED] != 0) {
            recentrouPendente = true;
        }
        if (agora < proximaEntradaNs) {
            return;
        }
        if (entradasPendentes.get() >= ENTRADA_MAX_PENDENTES) {
            return;
        }
        proximaEntradaNs = Math.max(proximaEntradaNs + ENTRADA_INTERVALO_NS,
                agora - ENTRADA_INTERVALO_NS);
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"estado\":\"")
                .append(in[RIN_STATE] >= 2 ? "focado" : (in[RIN_STATE] >= 1 ? "visivel" : "oculto"))
                .append("\",\"cabeca\":");
        if (in[RIN_HEAD] != 0) {
            sb.append("{\"pos\":");
            arr(sb, in, RIN_HEAD + 1, 3);
            sb.append(",\"rot\":");
            arr(sb, in, RIN_HEAD + 4, 4);
            sb.append('}');
        } else {
            sb.append("null");
        }
        sb.append(",\"maos\":{\"esq\":");
        mao(sb, in, RIN_LEFT);
        sb.append(",\"dir\":");
        mao(sb, in, RIN_RIGHT);
        sb.append("},\"recentrou\":").append(recentrouPendente).append('}');
        recentrouPendente = false;
        final byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        entradasPendentes.incrementAndGet();
        estEntradas.incrementAndGet();
        try {
            envios.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        escrever(MSG_ENTRADA, payload);
                    } finally {
                        entradasPendentes.decrementAndGet();
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            entradasPendentes.decrementAndGet();
        }
    }

    // Chamado na thread do loop de quadros do XR, uma vez por quadro
    @Override
    public void onRemoteInput(float[] in) {
        if (conectado) {
            enviarEntrada(in);
        }
        // Sem camadas do PC: painel local na frente da cabeca
        if (!recebeuCamadas) {
            if (in[RIN_RECENTERED] != 0) {
                precisaPainel = true;
            }
            // Gatilho de qualquer controle: pede ao despertador do PC para abrir o Pop3D
            boolean gatilho = in[RIN_STATE] >= 2 && (in[RIN_LEFT + 8] > 0.5f || in[RIN_RIGHT + 8] > 0.5f);
            if (gatilho && !gatilhoAntes) {
                acordarPc();
            }
            gatilhoAntes = gatilho;
            if (precisaPainel && in[RIN_HEAD] != 0) {
                precisaPainel = false;
                mostrarPainelLocal(in);
            }
        }
    }

    private void aoConectar() {
        // O painel local fica ate chegar a primeira CAMADAS do PC. "adb reverse"
        // aceita a ligacao mesmo sem PC do outro lado e fecha logo a seguir; assim
        // isso nao faz o painel piscar.
        recebeuCamadas = false;
        conectado = true;
    }

    private void aoDesconectar() {
        if (!conectado) {
            return;
        }
        conectado = false;
        XrRenderer sessao = xr;
        if (sessao != null && recebeuCamadas) {
            recebeuCamadas = false;
            precisaPainel = true;
            sessao.remoteSetLayers(new String[0], new float[0], 0);
            sessao.remoteSetPassthrough(false);
            desenharPainelLocal(AVISO_GATILHO);
            LimeLog.info(TAG + "camadas limpas, painel local de volta");
        }
    }

    // ------------------------------------------------------------------
    // Painel local "Procurando o seu PC"

    private static final int PAINEL_W = 1280;
    private static final int PAINEL_H = 640;
    private static final int AVISO_GATILHO = 0, AVISO_ABRINDO = 1, AVISO_SEM_RESPOSTA = 2;
    private boolean gatilhoAntes;
    private volatile long acordouMs;

    // O despertador (despertador.py, de pe desde o login do Windows) abre o Pop3D do PC. Wi-Fi: broadcast UDP;
    // USB: 127.0.0.1 (adb reverse feito pelo proprio despertador). Responde POP3D_ABRINDO ou POP3D_ABERTO.
    private void acordarPc() {
        long agora = System.currentTimeMillis();
        if (agora - acordouMs < 3000) {
            return;
        }
        acordouMs = agora;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean respondeu = false;
                byte[] pedido = "POP3D_ABRIR".getBytes(StandardCharsets.US_ASCII);
                try {
                    Socket usb = new Socket();
                    usb.connect(new InetSocketAddress("127.0.0.1", PORTA_DESPERTADOR), 400);
                    usb.setSoTimeout(1500);
                    usb.getOutputStream().write(pedido);
                    byte[] buf = new byte[64];
                    respondeu = usb.getInputStream().read(buf) > 0;
                    usb.close();
                } catch (IOException ignored) {
                }
                if (!respondeu) {
                    adquirirMulticast();
                    DatagramSocket ds = null;
                    try {
                        ds = new DatagramSocket();
                        ds.setBroadcast(true);
                        ds.setSoTimeout(1500);
                        ds.send(new DatagramPacket(pedido, pedido.length,
                                InetAddress.getByName("255.255.255.255"), PORTA_DESPERTADOR));
                        byte[] buf = new byte[64];
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        ds.receive(resp);
                        respondeu = new String(buf, 0, resp.getLength(), StandardCharsets.US_ASCII)
                                .startsWith("POP3D_");
                    } catch (IOException ignored) {
                    } finally {
                        if (ds != null) {
                            ds.close();
                        }
                    }
                }
                LimeLog.info(TAG + "acordar o PC: " + (respondeu ? "respondeu" : "sem resposta"));
                if (!recebeuCamadas && xr != null) {
                    desenharPainelLocal(respondeu ? AVISO_ABRINDO : AVISO_SEM_RESPOSTA);
                }
            }
        }, "Pop3D - acordar PC").start();
    }

    private void montarPainelLocal() {
        desenharPainelLocal(AVISO_GATILHO);
    }

    private void desenharPainelLocal(int aviso) {
        Bitmap bmp = Bitmap.createBitmap(PAINEL_W, PAINEL_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        // Texturas sobem de baixo para cima: desenha espelhado e a carga endireita
        c.translate(0f, PAINEL_H);
        c.scale(1f, -1f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xEE141C2B);
        RectF r = new RectF(8, 8, PAINEL_W - 8, PAINEL_H - 8);
        c.drawRoundRect(r, 48, 48, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setColor(0xFF4F8CFF);
        c.drawRoundRect(r, 48, 48, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        // Idioma do \u00f3culos: portugu\u00eas, espanhol ou (o resto) ingl\u00eas
        String lingua = Locale.getDefault().getLanguage();
        String[] t = "pt".equals(lingua)
                ? new String[] {"Procurando o seu PC\u2026", "Abra o Pop3D no computador (mesmo Wi-Fi)",
                                "Ainda n\u00e3o tem? Baixe gr\u00e1tis em:",
                                "Aperte o gatilho para abrir o Pop3D no PC",
                                "Abrindo o Pop3D no PC\u2026 aguarde",
                                "O PC n\u00e3o respondeu: ligue o PC e abra o Pop3D nele"}
                : "es".equals(lingua)
                ? new String[] {"Buscando tu PC\u2026", "Abre Pop3D en la computadora (misma Wi-Fi)",
                                "\u00bfA\u00fan no lo tienes? Desc\u00e1rgalo gratis en:",
                                "Aprieta el gatillo para abrir Pop3D en la PC",
                                "Abriendo Pop3D en la PC\u2026 espera",
                                "La PC no respondi\u00f3: enci\u00e9ndela y abre Pop3D en ella"}
                : new String[] {"Looking for your PC\u2026", "Open Pop3D on your computer (same Wi-Fi)",
                                "Don't have it yet? Get it free at:",
                                "Press the trigger to open Pop3D on your PC",
                                "Opening Pop3D on your PC\u2026 please wait",
                                "Your PC didn't answer: turn it on and open Pop3D there"};
        p.setTextSize(88);
        c.drawText(t[0], PAINEL_W / 2f, 190, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(0xFFC9D4EA);
        p.setTextSize(46);
        c.drawText(t[1], PAINEL_W / 2f, 280, p);
        // o que fazer agora (aperte o gatilho / abrindo / sem resposta), em destaque
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setColor(aviso == AVISO_SEM_RESPOSTA ? 0xFFFFB86B : (aviso == AVISO_ABRINDO ? 0xFF7DE3A8 : 0xFFFFFFFF));
        p.setTextSize(44);
        c.drawText(t[3 + aviso], PAINEL_W / 2f, 380, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(0xFF8E9AB5);
        p.setTextSize(38);
        c.drawText(t[2], PAINEL_W / 2f, 480, p);
        p.setColor(0xFF7FB0FF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(46);
        c.drawText("pop3d.quest", PAINEL_W / 2f, 548, p);
        ByteBuffer buf = ByteBuffer.allocateDirect(PAINEL_W * PAINEL_H * 4)
                .order(ByteOrder.nativeOrder());
        bmp.copyPixelsToBuffer(buf);
        buf.rewind();
        bmp.recycle();
        painelPixels = buf;
        xr.remoteSetRawTexture(TEX_LOCAL, PAINEL_W, PAINEL_H, buf);
    }

    // in: cabeca em RIN_HEAD+1.. (pos, rot). O painel fica 1,5 m a frente da
    // direcao do olhar (so o giro horizontal), na altura da cabeca.
    private void mostrarPainelLocal(float[] in) {
        XrRenderer sessao = xr;
        if (sessao == null) {
            return;
        }
        float hx = in[RIN_HEAD + 1], hy = in[RIN_HEAD + 2], hz = in[RIN_HEAD + 3];
        float qx = in[RIN_HEAD + 4], qy = in[RIN_HEAD + 5], qz = in[RIN_HEAD + 6],
                qw = in[RIN_HEAD + 7];
        // frente = q * (0,0,-1)
        float fx = -2f * (qx * qz + qw * qy);
        float fz = -(1f - 2f * (qx * qx + qy * qy));
        double giro = Math.hypot(fx, fz) > 0.2 ? Math.atan2(-fx, -fz) : 0.0;
        float dist = 1.5f;
        String[] ids = new String[] { TEX_LOCAL };
        float[] d = new float[RL_STRIDE];
        d[RL_KIND] = RL_KIND_QUAD;
        d[RL_ALPHA] = 2; // pre-multiplicada: vem de um Bitmap do Android
        d[RL_POS] = hx + (float) (-Math.sin(giro) * dist);
        d[RL_POS + 1] = hy;
        d[RL_POS + 2] = hz + (float) (-Math.cos(giro) * dist);
        d[RL_ROT + 1] = (float) Math.sin(giro / 2);
        d[RL_ROT + 3] = (float) Math.cos(giro / 2);
        d[RL_SIZE] = 1.3f;
        d[RL_SIZE + 1] = 0.65f;
        sessao.remoteSetLayers(ids, d, 1);
        LimeLog.info(TAG + "painel local posto a frente da cabeca");
    }

    @Override
    public void onPerfUpdate(final String text) {
    }
}
