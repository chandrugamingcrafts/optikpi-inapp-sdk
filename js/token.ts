export type PlayerTokenClaims = {
  playerId: string;
  workspaceId: string;
};

function decodeBase64Url(value: string): string {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const pad = padded.length % 4 === 0 ? "" : "=".repeat(4 - (padded.length % 4));
  return atob(padded + pad);
}

/** `sub` and `wid` of a JWT payload, unverified — same as the Flutter SDK. */
export function readPlayerTokenClaims(token: string): PlayerTokenClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const claims = JSON.parse(decodeBase64Url(parts[1])) as Record<string, unknown>;
    const sub = claims.sub;
    const wid = claims.wid;
    if (typeof sub !== "string" || !sub || typeof wid !== "string" || !wid) {
      return null;
    }
    return { playerId: sub, workspaceId: wid };
  } catch {
    return null;
  }
}
