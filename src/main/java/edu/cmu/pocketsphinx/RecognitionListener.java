package edu.cmu.pocketsphinx;

import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.NBestList;

public interface RecognitionListener {

    /**
     * Called at the start of utterance.
     */
    public void onBeginningOfSpeech();

    /**
     * Called at the end of utterance.
     */
    public void onEndOfSpeech();

    /**
     * Called when partial recognition result is available.
     */
    public void onPartialResult(Hypothesis hypothesis, NBestList nbest);

    /**
     * Called after the recognition is ended.
     */
    public void onResult(Hypothesis hypothesis, NBestList nbest);

    /**
     * Gives the read data. This must not block. Client must copy the data immediately if needed.
     * 
     * This is not called on UI thread.
     */
    public void onRead(short[] buffer, int offset, int nread);

    public void onReady();
    public void onError(Exception e);
}

/* vim: set ts=4 sw=4: */