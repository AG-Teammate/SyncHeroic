#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const credentialPath = process.argv[2] ?? ".private/trainheroic.env";
const rawCredentials = await readFile(credentialPath, "utf8");
const credentials = Object.fromEntries(
  rawCredentials
    .split(/\r?\n/)
    .filter((line) => line.trim() && !line.trimStart().startsWith("#"))
    .map((line) => {
      const separator = line.indexOf("=");
      if (separator < 1) throw new Error("Invalid credential file format");
      return [line.slice(0, separator).trim(), line.slice(separator + 1)];
    }),
);

if (!credentials.TRAINHEROIC_EMAIL || !credentials.TRAINHEROIC_PASSWORD) {
  throw new Error("Credential file must define TRAINHEROIC_EMAIL and TRAINHEROIC_PASSWORD");
}

const authResponse = await fetch("https://apis.trainheroic.com/auth", {
  method: "POST",
  headers: { accept: "application/json", "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({
    email: credentials.TRAINHEROIC_EMAIL,
    password: credentials.TRAINHEROIC_PASSWORD,
  }),
});
if (!authResponse.ok) throw new Error(`Authentication failed with HTTP ${authResponse.status}`);
const auth = await authResponse.json();
if (typeof auth.session_id !== "string" || typeof auth.id !== "number") {
  throw new Error("Authentication response did not contain the expected session shape");
}

const headers = { accept: "application/json", "session-token": auth.session_id };
async function getJson(url, label) {
  const response = await fetch(url, { headers });
  if (!response.ok) throw new Error(`${label} failed with HTTP ${response.status}`);
  return response.json();
}

const user = await getJson("https://api.trainheroic.com/user/simple", "Profile");
if (typeof user.id !== "number") throw new Error("Profile did not contain a numeric user ID");
const summary = await getJson(
  `https://api.trainheroic.com/v5/athleteProfile/summary?user_id=${encodeURIComponent(user.id)}&use_metric=0`,
  "Profile summary",
);

function iso(date) {
  return date.toISOString().slice(0, 10);
}
function windows(start, end, days) {
  const output = [];
  let cursor = new Date(`${start}T00:00:00Z`);
  const final = new Date(`${end}T00:00:00Z`);
  while (cursor <= final) {
    const windowEnd = new Date(Math.min(cursor.getTime() + (days - 1) * 86_400_000, final.getTime()));
    output.push([iso(cursor), iso(windowEnd)]);
    cursor = new Date(windowEnd.getTime() + 86_400_000);
  }
  return output;
}

const ranges = windows("2000-01-01", iso(new Date()), 180);
const pages = new Array(ranges.length);
let cursor = 0;
await Promise.all(
  Array.from({ length: 3 }, async () => {
    while (cursor < ranges.length) {
      const index = cursor++;
      const [start, end] = ranges[index];
      const data = await getJson(
        `https://api.trainheroic.com/3.0/athlete/programworkout/range?startDate=${start}&endDate=${end}`,
        `Workout window ${index + 1}`,
      );
      if (!Array.isArray(data)) throw new Error(`Workout window ${index + 1} was not an array`);
      pages[index] = data;
    }
  }),
);

const allRows = pages.flat();
const unique = new Map();
for (const row of allRows) {
  const key = row && row.id != null ? String(row.id) : `missing-${unique.size}`;
  if (!unique.has(key)) unique.set(key, row);
}
const workouts = [...unique.values()];

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function logged(row) {
  const saved = row?.summarizedSavedWorkout?.saved_workout;
  if (!isRecord(saved)) return false;
  for (const setKey of ["workoutSets", "addedWorkoutSets"]) {
    for (const set of Array.isArray(saved[setKey]) ? saved[setKey] : []) {
      for (const exercise of Array.isArray(set?.workoutSetExercises) ? set.workoutSetExercises : []) {
        for (let slot = 1; slot <= 10; slot += 1) {
          if (Number(exercise?.[`param_${slot}_made`]) === 1) return true;
        }
      }
    }
  }
  return false;
}

const dateCounts = new Map();
for (const row of workouts) {
  if (typeof row?.date === "string") dateCounts.set(row.date, (dateCounts.get(row.date) ?? 0) + 1);
}

function epoch(value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) return null;
  return new Date(value > 10_000_000_000 ? value : value * 1000);
}

const loggedRows = workouts.filter(logged);
const timestampAnalysis = {
  loggedWithStart: 0,
  loggedWithEnd: 0,
  loggedWithBoth: 0,
  positiveDurations: 0,
  startsWithinOneDayOfWorkoutDate: 0,
  durationMinutes: [],
};
for (const row of loggedRows) {
  const saved = row?.summarizedSavedWorkout?.saved_workout;
  const start = epoch(saved?.timestamp_started);
  const end = epoch(saved?.timestamp_completed);
  if (start) timestampAnalysis.loggedWithStart += 1;
  if (end) timestampAnalysis.loggedWithEnd += 1;
  if (start && typeof row.date === "string") {
    const scheduled = new Date(`${row.date.slice(0, 10)}T12:00:00Z`);
    if (Math.abs(start.getTime() - scheduled.getTime()) <= 36 * 3_600_000) {
      timestampAnalysis.startsWithinOneDayOfWorkoutDate += 1;
    }
  }
  if (start && end) {
    timestampAnalysis.loggedWithBoth += 1;
    const durationMinutes = (end.getTime() - start.getTime()) / 60_000;
    if (durationMinutes > 0) timestampAnalysis.positiveDurations += 1;
    if (durationMinutes > 0 && durationMinutes < 24 * 60) timestampAnalysis.durationMinutes.push(durationMinutes);
  }
}
timestampAnalysis.durationMinutes.sort((a, b) => a - b);
const durations = timestampAnalysis.durationMinutes;
const timestampSummary = {
  loggedWithStart: timestampAnalysis.loggedWithStart,
  loggedWithEnd: timestampAnalysis.loggedWithEnd,
  loggedWithBoth: timestampAnalysis.loggedWithBoth,
  positiveDurations: timestampAnalysis.positiveDurations,
  startsWithinOneDayOfWorkoutDate: timestampAnalysis.startsWithinOneDayOfWorkoutDate,
  plausibleDurationMinutes: durations.length
    ? {
        count: durations.length,
        minimum: Math.round(durations[0]),
        median: Math.round(durations[Math.floor(durations.length / 2)]),
        maximum: Math.round(durations.at(-1)),
      }
    : null,
};

let performedVolume = 0;
let volumePairs = 0;
for (const row of loggedRows) {
  const saved = row?.summarizedSavedWorkout?.saved_workout;
  for (const setKey of ["workoutSets", "addedWorkoutSets"]) {
    for (const set of Array.isArray(saved?.[setKey]) ? saved[setKey] : []) {
      for (const exercise of Array.isArray(set?.workoutSetExercises) ? set.workoutSetExercises : []) {
        const firstType = Number(exercise?.param_1_type);
        const secondType = Number(exercise?.param_2_type);
        for (let slot = 1; slot <= 10; slot += 1) {
          if (Number(exercise?.[`param_${slot}_made`]) !== 1) continue;
          const first = Number(exercise?.[`param_1_data_${slot}`]);
          const second = Number(exercise?.[`param_2_data_${slot}`]);
          const reps = firstType === 3 ? first : secondType === 3 ? second : null;
          const weight = firstType === 1 ? first : secondType === 1 ? second : null;
          if (Number.isFinite(reps) && Number.isFinite(weight)) {
            performedVolume += reps * weight;
            volumePairs += 1;
          }
        }
      }
    }
  }
}

const shape = new Map();
function visit(value, path) {
  const type = value === null ? "null" : Array.isArray(value) ? "array" : typeof value;
  const row = shape.get(path) ?? { count: 0, nonNull: 0, types: new Set() };
  row.count += 1;
  if (value !== null && value !== undefined) row.nonNull += 1;
  row.types.add(type);
  shape.set(path, row);
  if (Array.isArray(value)) value.forEach((child) => visit(child, `${path}[]`));
  else if (isRecord(value)) Object.entries(value).forEach(([key, child]) => visit(child, path ? `${path}.${key}` : key));
}
workouts.forEach((workout) => visit(workout, "workouts[]"));

const timingPaths = [...shape]
  .filter(([path, row]) => /(?:time|duration|complete|start|end)/i.test(path) && row.nonNull > 0)
  .map(([path, row]) => ({ path, types: [...row.types].sort(), nonNull: row.nonNull }))
  .sort((a, b) => a.path.localeCompare(b.path));

const summaryCounters = [];
function findCounters(value, path = "summary") {
  if (isRecord(value)) {
    for (const [key, child] of Object.entries(value)) findCounters(child, `${path}.${key}`);
  } else if (typeof value === "number" && /(?:session|workout|volume|hour|count|total)/i.test(path)) {
    summaryCounters.push({ path, value });
  }
}
findCounters(summary);

const report = {
  authentication: {
    ok: true,
    responseFields: Object.keys(auth).sort(),
    hasRefreshToken: Object.keys(auth).some((key) => /refresh/i.test(key)),
    hasExpiry: Object.keys(auth).some((key) => /(?:expir|ttl)/i.test(key)),
  },
  history: {
    windowsRequested: ranges.length,
    nonEmptyWindows: pages.filter((page) => page.length > 0).length,
    largestWindow: Math.max(0, ...pages.map((page) => page.length)),
    rowsReturned: allRows.length,
    uniqueWorkoutIds: unique.size,
    duplicateIdsAcrossWindows: allRows.length - unique.size,
    loggedWorkouts: loggedRows.length,
    datesWithMultipleWorkouts: [...dateCounts.values()].filter((count) => count > 1).length,
  },
  profileSummaryCounters: summaryCounters,
  sessionTimestampAnalysis: timestampSummary,
  performedVolume: {
    parsedSetPairs: volumePairs,
    computedInSourceWeightUnit: Math.round(performedVolume),
    profileVolume: summaryCounters.find((item) => item.path === "summary.volume_sum")?.value ?? null,
  },
  timingRelatedShapePaths: timingPaths,
};

process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
