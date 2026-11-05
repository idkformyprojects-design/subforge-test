package android.os;
public class Handler { public Handler(Looper l) {} public boolean post(Runnable r) { r.run(); return true; }
  public boolean postDelayed(Runnable r, long ms) { new Thread(() -> { try { Thread.sleep(ms); } catch (InterruptedException e) {} r.run(); }).start(); return true; } }
