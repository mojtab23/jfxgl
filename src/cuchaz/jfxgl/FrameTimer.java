package cuchaz.jfxgl;

public class FrameTimer {

	private static final long NSpMS = 1000000;
	private static final long NSpS = NSpMS*1000;
	private static final long UpdateIntervalNS = 1000*NSpMS; // 1 second
	private static final int MinNumFrames = 10;

	private int numFrames;
	private long startTime;
	private boolean isFirst;
	
	public FrameTimer() {
		numFrames = 0;
		startTime = 0;
		isFirst = true;
	}

	public void update() {
		
		long now = System.nanoTime();

		if (isFirst) {
			isFirst = false;
			numFrames = 0;
			startTime = now;
			return;
		}
		
		// look, a frame!
		numFrames++;

		// should we update our estimate?
		long elapsed = now - startTime;
		if (elapsed > UpdateIntervalNS && numFrames > MinNumFrames) {
			
			// yup
			System.out.println(String.format("FPS: %.1f", (float)numFrames*NSpS/elapsed));
			
			// and reset the counters
			startTime = now;
			numFrames = 0;
		}
	}
}
