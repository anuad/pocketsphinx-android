/* ====================================================================
 * Copyright (c) 2014 Alpha Cephei Inc.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY ALPHA CEPHEI INC. ``AS IS'' AND
 * ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL CARNEGIE MELLON UNIVERSITY
 * NOR ITS EMPLOYEES BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ====================================================================
 */

package edu.cmu.pocketsphinx;

import static java.lang.String.format;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.Collection;
import java.util.HashSet;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;

import android.os.*;

import android.util.Log;

import edu.cmu.pocketsphinx.Config;
import edu.cmu.pocketsphinx.Decoder;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.NBestList;
/**
 * Main class to access recognizer functions. After configuration this class
 * starts a listener thread which records the data and recognizes it using
 * Pocketsphinx engine. Recognition events are passed to a client using
 * {@link RecognitionListener}
 * 
 */
public class SpeechRecognizer {

  protected static final String TAG = SpeechRecognizer.class.getSimpleName();

  public static int BUFFER_SIZE = 2 * 1024;
  public static int INPUT_SIZE = 16 * 1024;

  public static boolean DEBUG_MSGS_ALLOWED = true;

  private final Decoder decoder;

  private RecognizerThread recognizerThread;
  private final Handler mainHandler;
  protected final Collection<RecognitionListener> listeners = new HashSet<RecognitionListener>();

  private final int sampleRate;
  private long minSpeechTimeMilis = 2000;
  private long maxSpeechTimeMilis = 5000;

  protected SpeechRecognizer(Config config) {
    sampleRate = (int) config.getFloat("-samprate");
    if (config.getFloat("-samprate") != sampleRate)
      throw new IllegalArgumentException("sampling rate must be integer");

    decoder = new Decoder(config);
    mainHandler = new Handler(Looper.getMainLooper());
  }

  /**
   * Meant only for testing.
   * @param forTesting
   */
  protected SpeechRecognizer(boolean forTesting) {
    if (!forTesting) {
      throw new IllegalArgumentException("This constructor is meant for testing only");
    }
    sampleRate = -1;
    decoder = null;
    mainHandler = null;
  }

  public void setMinSpeechTime(long timeMilis) {
    this.minSpeechTimeMilis = timeMilis;
  }
  public void setMaxSpeechTime(long timeMilis) {
    this.maxSpeechTimeMilis = timeMilis;
  }

  /**
   * Adds listener.
   */
  public void addListener(RecognitionListener listener) {
    synchronized (listeners) {
      listeners.add(listener);
    }
  }

  /**
   * Removes listener.
   */
  public void removeListener(RecognitionListener listener) {
    synchronized (listeners) {
      listeners.remove(listener);
    }
  }

  /**
   * Starts recognition. Does nothing if recognition is active.
   * 
   * @return true if recognition was actually started
   */
  public boolean startListening(String searchName) {
    if (null != recognizerThread && recognizerThread.isAlive())
      return false;

    if (DEBUG_MSGS_ALLOWED) Log.i(TAG, format("Start recognition \"%s\"", searchName));
    decoder.setSearch(searchName);
    recognizerThread = new RecognizerThread(new AudioRecordSource());
    recognizerThread.start();
    return true;
  }

  /**
   * Starts recognition. After specified timeout listening stops and the
   * endOfSpeech signals about that. Does nothing if recognition is active.
   * 
   * @timeout - timeout in milliseconds to listen.
   * 
   * @return true if recognition was actually started
   */
  public boolean startListening(String searchName, int timeout) {
          return false;
  }

  /**
   * Stops recognition. All listeners should receive final result if there is
   * any. Does nothing if recognition is not active.
   * 
   * @return true if recognition was actually stopped
   */
  public boolean stop() {
    if (null == recognizerThread)
      return false;

    try {
      recognizerThread.interrupt();
      recognizerThread.join();
    } catch (InterruptedException e) {
      // Restore the interrupted status.
      Thread.currentThread().interrupt();
    }

    recognizerThread = null;

    if (DEBUG_MSGS_ALLOWED) {
      Log.i(TAG, "Stop recognition");
    }

    return true;
  }

  /**
   * Cancels recogition. Listeners do not recevie final result. Does nothing if
   * recognition is not active.
   * 
   * @return true if recognition was actually canceled
   */
  public boolean cancel() {
    if (recognizerThread != null) {
      recognizerThread.cancelled = true;
    }
    if (DEBUG_MSGS_ALLOWED) Log.i(TAG, "Cancel recognition");
    mainHandler.removeCallbacksAndMessages(null);
    recognizerThread = null;
    return true;
  }
    
    /**
     * Gets name of the currently active search.
     * 
     * @return active search name or null if no search was started
     */
    public String getSearchName() {
        return decoder.getSearch();
    }

    public void addFsgSearch(String searchName, FsgModel fsgModel) {
        decoder.setFsg(searchName, fsgModel);
    }

    /**
     * Adds searches based on JSpeech grammar.
     * 
     * @param name
     *            search name
     * @param file
     *            JSGF file
     */
    public void addGrammarSearch(String name, File file) {
        Log.i(TAG, format("Load JSGF %s", file));
        decoder.setJsgfFile(name, file.getPath());
    }

    /**
     * Adds search based on N-gram language model.
     * 
     * @param name
     *            search name
     * @param file
     *            N-gram model file
     */
    public void addNgramSearch(String name, File file) {
        Log.i(TAG, format("Load N-gram model %s", file));
        decoder.setLmFile(name, file.getPath());
    }

    /**
     * Adds search based on a single phrase.
     * 
     * @param name
     *            search name
     * @param phrase
     *            search phrase
     */
    public void addKeyphraseSearch(String name, String phrase) {
        decoder.setKeyphrase(name, phrase);
    }


	/**
	 * Adds search based on a single phrase.
	 * 
	 * @param name
	 *            search name
	 * @param phrase
	 *            search phrase
	 */
	public void addKeywordSearch(String name, String phrase) {
		decoder.setKws(name, phrase);
	}

    /**
     * Returns the decoder object for advanced operation (dictionary extension, utterance
     * data collection, adaptation and so on).
     * 
     * @return Decoder
     */
    public Decoder getDecoder() {
        return decoder;
    }
    

    /**
     * Adds search based on a keyphrase file.
     * 
     * @param name
     *            search name
     * @param phrase
     *            a file with search phrases, one phrase per line with optional weight in the end, for example
     *            <br/>
     *            <code>
     *            oh mighty computer /1e-20/
     *            how do you do /1e-10/
     *            </code>
     */
    public void addKeywordSearch(String name, File file) {
        decoder.setKws(name, file.getPath());
    }
    
    /**
     * Adds a search to look for the phonemes
     *
     * @param name
     *          search name
     * @param phonetic bigram model
     * 
     */
    public void addAllphoneSearch(String name, File file) {
        decoder.setAllphoneFile(name, file.getPath());
    }
  public interface SoundSource {
	    int read(short[] buffer, int offset, int length);
	    boolean start();
	    void stop();
	    void release();
	  }

	  class InputStreamSource implements SoundSource {
	    private final InputStream stream;
	    private boolean finished;
	    InputStreamSource(InputStream stream) {
	      this.stream = stream;
	    }

	    @Override
	    public int read(short[] buffer, int offset, int length) {
	      if (finished) {
	        return 0;
	      }

	      try {
	        int bytelength = 2 * length;
	        byte[] barr = new byte[bytelength];
	        int nread = stream.read(barr);
	        if (nread == -1) {
	          finished = true;
	          Thread.currentThread().interrupt();
	          return 0;
	        }

	        ByteBuffer.wrap(barr).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer, offset, nread/2);
	        return nread/2;
	      } catch (IOException e) {
	        throw new IllegalStateException(e);
	      }
	    }

	    @Override
	    public boolean start() {
	      return true;
	    }

	    @Override
	    public void stop() {
	    }

	    @Override
	    public void release() {
	      try {
	        stream.close();
	      } catch (IOException e) {
	        throw new IllegalStateException(e);
	      }
	    }
	  }

	  // This is a hack, sometimes audiorecorder.record is throwing illegalstateexception
	  // this can happen if it is not released from an earlier use.
	  private static AudioRecordSource audioRecordSource;

	  class AudioRecordSource implements SoundSource {
	    AudioRecord recorder;
	    AudioEffect suppressor;

	    @Override
	    public int read(short[] buffer, int offset, int length) {
	      return recorder.read(buffer, offset, length);
	    }

	    @Override
	    public boolean start() {
	      synchronized (TAG) {
	        if (audioRecordSource != null) {
	          audioRecordSource.stop();
	          audioRecordSource.release();
	        }
	        if (suppressor != null) {
	          suppressor.release();
	        }
	        audioRecordSource = this;
	      }
	      recorder = new AudioRecord(AudioSource.VOICE_RECOGNITION,
	          sampleRate, AudioFormat.CHANNEL_IN_MONO,
	          AudioFormat.ENCODING_PCM_16BIT, INPUT_SIZE);
	      if (Build.VERSION.SDK_INT >= 16) {
	        if (NoiseSuppressor.isAvailable()) {
	          suppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
	          if (!suppressor.getEnabled()) {
	            suppressor.setEnabled(true);
	          }
	        }
	      }

	      if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED) {
	        release();
	        return false;
	      }

	      recorder.startRecording();
	      return true;
	    }

	    @Override
	    public void stop() {
	      if (recorder.getState() == AudioRecord.STATE_INITIALIZED &&
	          recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
	        recorder.stop();
	      }
	    }

	    @Override
	    public void release() {
	      synchronized (TAG) {
	        if (audioRecordSource == this) {
	          audioRecordSource = null;
	        }
	        if (suppressor != null) {
	          suppressor.release();
	        }
	      }
	      recorder.release();
	    }
	  }

	  public void process(String searchName, InputStream stream) {
		    decoder.setSearch(searchName);
		    recognizerThread = new RecognizerThread(new InputStreamSource(stream));
		    recognizerThread.start();
		  }

}
  private final class RecognizerThread extends Thread {
    private SoundSource source;
    private long startTime;
    private boolean cancelled;
    private boolean eosSent;

    public RecognizerThread(SoundSource source) {
      this.source = source;
    }

    @Override
    public void run() {
      if (!source.start()) {
        mainHandler.post(new OnErrorEvent(
        		new IOException(
                        "Failed to start recording. Microphone might be already in use.")));
        return;
      }

      decoder.startUtt();
      mainHandler.post(new StartEvent());
      short[] buffer = new short[BUFFER_SIZE];
      boolean startedSpeaking = false;

      startTime = System.currentTimeMillis();
      while (!interrupted() && !cancelled
          && System.currentTimeMillis() - startTime < maxSpeechTimeMilis) {
        int nread = source.read(buffer, 0, buffer.length);

        if (-1 == nread) {
          break;
        } else if (nread > 0) {
          decoder.processRaw(buffer, nread, false, false);

          RecognitionListener[] emptyArray = {};
          for (RecognitionListener listener : listeners.toArray(emptyArray)) {
            listener.onRead(buffer, 0, nread);
          }

          if (decoder.getInSpeech() && !startedSpeaking) {
            startedSpeaking = true;
            System.out.println("Started speaking");
            mainHandler.post(new InSpeechChangeEvent(true));
          }
          if (!decoder.getInSpeech() && startedSpeaking) {
            // Speaker is silent now.
            if (System.currentTimeMillis() - startTime > minSpeechTimeMilis) {
              if (!eosSent) {
                eosSent = true;
                System.out.println("Stopped speaking");
                mainHandler.post(new InSpeechChangeEvent(false));
              }
            }
          }
          
          final Hypothesis hypothesis = decoder.hyp();
          final NBestList nbestList = decoder.nbest();
          if (null != hypothesis)
            mainHandler.post(new ResultEvent(hypothesis, false,nbestList));
        }
      }

      source.stop();
      int nread = source.read(buffer, 0, buffer.length);
      source.release();
      if (nread > 0) {
        decoder.processRaw(buffer, nread, false, false);
      }
      decoder.endUtt();
      

      // Remove all pending notifications.
      mainHandler.removeCallbacksAndMessages(null);
      if (!cancelled) {
        final Hypothesis hypothesis = decoder.hyp();
        final NBestList nbestList = decoder.nbest();
        if (null != hypothesis) {
          mainHandler.post(new ResultEvent(hypothesis, true,nbestList));
        } else {
          mainHandler.post(new ResultEvent(null, true,nbestList));
        }
      }
    }
  }

  private class StartEvent extends RecognitionEvent {
    @Override
    protected void execute(RecognitionListener listener) {
      listener.onReady();
    }
  }

  private abstract class RecognitionEvent implements Runnable {
      public void run() {
          RecognitionListener[] emptyArray = new RecognitionListener[0];
          for (RecognitionListener listener : listeners.toArray(emptyArray))
              execute(listener);
      }

      protected abstract void execute(RecognitionListener listener);
  }

  private class InSpeechChangeEvent extends RecognitionEvent {
      private final boolean state;

      InSpeechChangeEvent(boolean state) {
          this.state = state;
      }

      @Override
      protected void execute(RecognitionListener listener) {
          if (state)
              listener.onBeginningOfSpeech();
          else
              listener.onEndOfSpeech();
      }
  }

  private class ResultEvent extends RecognitionEvent {
      protected final Hypothesis hypothesis;
      private final boolean finalResult;
      protected final NBestList nbestList;

      ResultEvent(Hypothesis hypothesis, boolean finalResult, NBestList nbestList) {
          this.hypothesis = hypothesis;
          this.finalResult = finalResult;
          this.nbestList = nbestList;
      }

      @Override
      protected void execute(RecognitionListener listener) {
          if (finalResult)
              listener.onResult(hypothesis,nbestList);
          else
              listener.onPartialResult(hypothesis,nbestList);
      }
  }

  private class OnErrorEvent extends RecognitionEvent {
      private final Exception exception;

      OnErrorEvent(Exception exception) {
          this.exception = exception;
      }

      @Override
      protected void execute(RecognitionListener listener) {
          listener.onError(exception);
      }
  }

