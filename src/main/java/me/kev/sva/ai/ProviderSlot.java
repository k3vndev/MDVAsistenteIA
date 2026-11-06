package me.kev.sva.ai;

/** Which configured provider a call goes to. */
public enum ProviderSlot {
  PRIMARY,
  FALLBACK;

  /** Maps Isolda's legacy int index (0 = primary, 1 = fallback). Unknown values mean primary. */
  public static ProviderSlot fromIndex(int index) {
    return index == 1 ? FALLBACK : PRIMARY;
  }

  public int index() {
    return this == FALLBACK ? 1 : 0;
  }
}
