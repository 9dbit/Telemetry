import fs from "node:fs";
import path from "node:path";

const root = path.join(process.cwd(), "node_modules", "expo-modules-jsi");
const pkgPath = path.join(root, "package.json");
const headerPath = path.join(root, "apple/Sources/ExpoModulesJSI-Cxx/include/RuntimeScheduler.h");
const swiftPath = path.join(root, "apple/Sources/ExpoModulesJSI/Runtime/JavaScriptRuntime.swift");

if (!fs.existsSync(pkgPath)) throw new Error("expo-modules-jsi is missing after npm install");
if (!fs.existsSync(headerPath) || !fs.existsSync(swiftPath)) {
  throw new Error("expo-modules-jsi compatibility source files are missing");
}

const version = JSON.parse(fs.readFileSync(pkgPath, "utf8")).version;
if (version !== "57.1.0") {
  throw new Error(`Review Expo compatibility patch for expo-modules-jsi ${version}; verified version is 57.1.0`);
}

let header = fs.readFileSync(headerPath, "utf8");
let swift = fs.readFileSync(swiftPath, "utf8");
let changed = false;

const ctorNeedle = "SWIFT_RETURNS_RETAINED RuntimeScheduler(";
const ctorMacro = "EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED RuntimeScheduler(";
if (header.includes(ctorNeedle)) {
  if (!header.includes("#define EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED")) {
    const marker = "public:\n";
    if (!header.includes(marker)) throw new Error("RuntimeScheduler public marker changed upstream");
    const compatibility = [
      "public:",
      "#if defined(__apple_build_version__) && __apple_build_version__ >= 18000000",
      "#define EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED SWIFT_RETURNS_RETAINED",
      "#else",
      "#define EXPO_RUNTIME_SCHEDULER_CTOR_RETAINED",
      "#endif",
      "",
    ].join("\n");
    header = header.replace(marker, compatibility);
  }
  header = header.split(ctorNeedle).join(ctorMacro);
  changed = true;
}

const resultDecl = "nonisolated(unsafe) let resultPtr = resultPtr";
const thisDecl = "nonisolated(unsafe) let thisPtr = thisPtr";
const argumentsDecl = "nonisolated(unsafe) let argumentsPtr = argumentsPtr";
const needsSwiftPatch = swift.includes(resultDecl) || swift.includes(thisDecl) || swift.includes(argumentsDecl);

if (needsSwiftPatch) {
  if (!swift.includes("struct TelemetryUnsafeSendableBox")) {
    const importMarker = "internal import jsi\n";
    if (!swift.includes(importMarker)) throw new Error("JavaScriptRuntime import marker changed upstream");
    const box = `internal import jsi\n\n// Temporary Swift 6.2 compatibility for Expo issue #50067.\nprivate struct TelemetryUnsafeSendableBox<Value>: @unchecked Sendable {\n  let value: Value\n  @inline(__always) init(_ value: Value) { self.value = value }\n}\n`;
    swift = swift.replace(importMarker, box);
  }

  swift = swift.split(resultDecl).join("let resultPtr = TelemetryUnsafeSendableBox(resultPtr)");
  swift = swift.split(thisDecl).join("let thisPtr = TelemetryUnsafeSendableBox(thisPtr)");
  swift = swift.split(argumentsDecl).join("let argumentsPtr = TelemetryUnsafeSendableBox(argumentsPtr)");
  swift = swift.split("writeJSIValue(to: resultPtr)").join("writeJSIValue(to: resultPtr.value)");
  swift = swift.split("UnsafeMutablePointer(mutating: thisPtr).move()").join("UnsafeMutablePointer(mutating: thisPtr.value).move()");
  swift = swift.split("JavaScriptValuesBuffer(runtime, start: argumentsPtr, count: argumentsCount)").join("JavaScriptValuesBuffer(runtime, start: argumentsPtr.value, count: argumentsCount)");
  swift = swift.split("JavaScriptUnownedValue(runtime.pointee, thisPtr)").join("JavaScriptUnownedValue(runtime.pointee, thisPtr.value)");
  changed = true;
}

if (changed) {
  fs.writeFileSync(headerPath, header);
  fs.writeFileSync(swiftPath, swift);
  console.log(`[telemetry] patched expo-modules-jsi ${version} for Xcode 26 / Swift 6.2+ compatibility`);
} else {
  console.log(`[telemetry] expo-modules-jsi ${version}: compatibility source already patched or fixed upstream`);
}
