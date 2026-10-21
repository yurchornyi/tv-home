package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Listens for a spoken search in the launcher's own field (MainActivity.startVoice), through the TV's recognition
 * service but without its screen: the words come back as they are heard (partial), then once more as the final
 * phrase; the loudness drives the microphone button's pulse. Needs RECORD_AUDIO (build-and-install grants it).
 */
final class VoiceSearch {

    interface Listener {
        void onHeard(String words, boolean done);
        void onLevel(float level);
        void onFailed();
    }

    private static final String TAG = "TvHome.Voice";

    private final Context ctx;
    private SpeechRecognizer recognizer;
    private boolean listening;

    VoiceSearch(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static boolean available(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    /** Starts listening; false when the TV has no recognition service. */
    boolean start(Listener listener) {
        stop();
        if (!available(ctx)) return false;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
        } catch (RuntimeException e) {
            Log.w(TAG, "no recognizer: " + e);
            return false;
        }
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) {
                // -2..10 dB in practice: 0..1 for the pulse.
                listener.onLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { listener.onLevel(0f); }
            @Override public void onError(int error) {
                listening = false;
                Log.i(TAG, "error " + error);
                listener.onFailed();
            }
            @Override public void onResults(Bundle results) {
                listening = false;
                String heard = first(results);
                if (heard.isEmpty()) listener.onFailed();
                else listener.onHeard(heard, true);
            }
            @Override public void onPartialResults(Bundle partial) {
                String heard = first(partial);
                if (!heard.isEmpty()) listener.onHeard(heard, false);
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot listen: " + e);
            stop();
            return false;
        }
        listening = true;
        return true;
    }

    boolean listening() {
        return listening;
    }

    void stop() {
        listening = false;
        if (recognizer == null) return;
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (RuntimeException ignored) {
        }
        recognizer = null;
    }

    private static String first(Bundle b) {
        ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
    }
}
