import fs from "node:fs";
import path from "node:path";

const root = path.join(process.cwd(), "node_modules", "expo-modules-jsi");
const pkgPath = path.join(root, "package.json");
if (!fs.existsSync(pkgPath)) throw new Error("expo-modules-jsi is missing after npm install");
const version = JSON.parse(fs.readFileSync(pkgPath, "utf8")).version;
const headerPath = path.join(root, "apple/Sources/ExpoModulesJSI-Cxx/include/RuntimeScheduler.h");
const swiftPath = path.join(root, "apple/Sources/ExpoModulesJSI/Runtime/JavaScriptRuntime.swift");

const count = (text, needle) => text.split(needle).length - 1;
function replaceExact(text, needle, replacement, expected, label) {
  const found = count(text, needle);
  if (found !== expected) {
    throw new Error(`${label}: expected ${expected}, found ${found}. Expo source changed; review the compatibility patch.`);
  }
  return text.split(needle).join(replacement);
}

if (!fs.existsSync(headerPath) || !fs.existsSync(swiftPath)) {
  throw new Error(`Unsupported expo-modules-jsi ${version}: SDK 57 source files are missing`);
}

let header = fs.readFileSync(headerPath, "utf8");
let swift = fs.readFileSync(swiftPath, "utf8");
const headerAffected = count(header, "SWIFT_RETURNS_RETAINED RuntimeScheduler(") === 2;
const swiftAffected = count(swift, "nonisolated(unsafe) let resultPtr = resultPtr") === 3
  && count(swift, "nonisolated(unsafe) let thisPtr = thisPtr") === 2
  && count(swift, "nonisolated(unsafe) let argumentsPtr = argumentsPtr") === 2;

if (!headerAffected && !swiftAffected) {
  console.log(`[telemetry] expo-modules-jsi ${version}: upstream compatibility fix detected; no patch needed`);
  process.exit(0);
}

const verifiedAffectedVersions = new Set(["57.1.0", "57.1.1"]);
if (!verifiedAffectedVersions.has(version)) {
  throw new Error(`expo-modules-jsi ${version} is affected, but this patch is verified only for ${[...verifiedAffectedVersions].join(", ")}`);
}

if (headerAffected) {
  header = replaceExact(header, "#include <swift/bridging>\n", `#include <swift/bridging>\n\n// Temporary compatibility for Expo issue #50067. Remove after upstream fix.\n#if defined(__apple_build_version__) && __apple_build_version__ >= 18000000\n#define EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED SWIFT_RETURNS_RETAINED\n#else\n#define EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED\n#endif\n`, 1, "ownership macro insertion");
  header = replaceExact(header, "SWIFT_RETURNS_RETAINED RuntimeScheduler(", "EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED RuntimeScheduler(", 2, "constructor ownership annotation");
  header = replaceExact(header, "\n#endif // __cplusplus", "\n#undef EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED\n\n#endif // __cplusplus", 1, "ownership macro cleanup");
  fs.writeFileSync(headerPath, header);
}

if (swiftAffected) {
  swift = replaceExact(swift, "nonisolated(unsafe) let resultPtr = resultPtr", "let resultPtr = NonisolatedUnsafeVar(resultPtr)", 3, "resultPtr wrapping");
  swift = replaceExact(swift, "nonisolated(unsafe) let thisPtr = thisPtr", "let thisPtr = NonisolatedUnsafeVar(thisPtr)", 2, "thisPtr wrapping");
  swift = replaceExact(swift, "nonisolated(unsafe) let argumentsPtr = argumentsPtr", "let argumentsPtr = NonisolatedUnsafeVar(argumentsPtr)", 2, "argumentsPtr wrapping");
  swift = replaceExact(swift, "writeJSIValue(to: resultPtr)", "writeJSIValue(to: resultPtr.value)", 3, "resultPtr use");
  swift = replaceExact(swift, "UnsafeMutablePointer(mutating: thisPtr).move()", "UnsafeMutablePointer(mutating: thisPtr.value).move()", 1, "owning thisPtr use");
  swift = replaceExact(swift, "JavaScriptValuesBuffer(runtime, start: argumentsPtr, count: argumentsCount)", "JavaScriptValuesBuffer(runtime, start: argumentsPtr.value, count: argumentsCount)", 2, "argumentsPtr use");
  swift = replaceExact(swift, "JavaScriptUnownedValue(runtime.pointee, thisPtr)", "JavaScriptUnownedValue(runtime.pointee, thisPtr.value)", 1, "unowned thisPtr use");
  fs.writeFileSync(swiftPath, swift);
}

console.log(`[telemetry] patched expo-modules-jsi ${version} for Swift 6.2 compatibility (Expo #50067)`);
