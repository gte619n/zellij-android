import { test, expect } from "bun:test";
import { spawnInGroup, killGroup, groupAlive } from "../../src/session/procgroup";

const tick = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

test("killGroup reaps the whole process group, including grandchildren", async () => {
  // sh stays as the group leader; `sleep` runs as a child in the same group.
  // This is the regression guard for the orphaned-grandchild bug (da870d5).
  const g = spawnInGroup("sh", ["-c", "sleep 30 & wait"]);
  await tick(100);
  expect(groupAlive(g.pgid)).toBe(true);

  await killGroup(g.pgid, 1500);
  await tick(100);
  expect(groupAlive(g.pgid)).toBe(false);
});

test("killGroup on an already-dead group is a no-op", async () => {
  const g = spawnInGroup("sh", ["-c", "exit 0"]);
  await g.exited;
  await tick(50);
  await killGroup(g.pgid, 500); // must not throw
  expect(groupAlive(g.pgid)).toBe(false);
});
