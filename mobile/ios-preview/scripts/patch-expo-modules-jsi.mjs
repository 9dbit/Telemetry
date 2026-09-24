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
let header = fs.readFileSync(headerPath, "utf8");
let swift = fs.readFileSync(swiftPath, "utf8");
let headerChanged = false;
let swiftChanged = false;

// Xcode 26 rejects SWIFT_RETURNS_RETAINED on these constructors.
// Removing only this annotation keeps runtime behavior unchanged and is the
// smallest workaround for Expo issue #50067.
const ctorNeedle = "SWIFT_RETURNS_RETAINED RuntimeScheduler(";
if (header.includes(ctorNeedle)) {
  header = header.split(ctorNeedle).join("RuntimeScheduler(");
  headerChanged = true;
}

// Swift 6.2 rejects raw-pointer captures across JavaScriptActor closures.
// Wrap only the call-scoped pointers that are present in the affected source.
const resultDecl = "nonisolated(unsafe) let resultPtr = resultPtr";
const thisDecl = "nonisolated(unsafe) let thisPtr = thisPtr";
const argumentsDecl = "nonisolated(unsafe) let argumentsPtr = argumentsPtr";
const needsSwiftPatch = swift.includes(resultDecl) || swift.includes(thisDecl) || swift.includes(argumentsDecl);

if (needsSwiftPatch) {
  if (!swift.includes("struct TelemetryUnsafeSendableBox")) {
    const helper = [
      "",
      "// Temporary Swift 6.2 compatibility for Expo issue #50067.",
      "private struct TelemetryUnsafeSendableBox<Value>: @unchecked Sendable {",
      "  let value: Value",
      "  @inline(__always) init(_ value: Value) { self.value = value }",
      "}",
      "",
    ].join("\n");

    if (swift.includes("internal import jsi\n")) {
      swift = swift.replace("internal import jsi\n", `internal import jsi\n${helper}`);
    } else if (swift.includes("import Foundation\n")) {
      swift = swift.replace("import Foundation\n", `import Foundation\n${helper}`);
    } else {
      throw new Error("Cannot locate a safe insertion point in JavaScriptRuntime.swift");
    }
  }

  swift = swift.split(resultDecl).join("let resultPtr = TelemetryUnsafeSendableBox(resultPtr)");
  swift = swift.split(thisDecl).join("let thisPtr = TelemetryUnsafeSendableBox(thisPtr)");
  swift = swift.split(argumentsDecl).join("let argumentsPtr = TelemetryUnsafeSendableBox(argumentsPtr)");
  swift = swift.split("writeJSIValue(to: resultPtr)").join("writeJSIValue(to: resultPtr.value)");
  swift = swift.split("UnsafeMutablePointer(mutating: thisPtr).move()").join("UnsafeMutablePointer(mutating: thisPtr.value).move()");
  swift = swift.split("JavaScriptValuesBuffer(runtime, start: argumentsPtr, count: argumentsCount)").join("JavaScriptValuesBuffer(runtime, start: argumentsPtr.value, count: argumentsCount)");
  swift = swift.split("JavaScriptUnownedValue(runtime.pointee, thisPtr)").join("JavaScriptUnownedValue(runtime.pointee, thisPtr.value)");
  swiftChanged = true;
}

if (headerChanged) fs.writeFileSync(headerPath, header);
if (swiftChanged) fs.writeFileSync(swiftPath, swift);

if (headerChanged || swiftChanged) {
  console.log(`[telemetry] expo-modules-jsi ${version}: applied Xcode 26 compatibility patch (header=${headerChanged}, swift=${swiftChanged})`);
} else {
  console.log(`[telemetry] expo-modules-jsi ${version}: no affected compatibility patterns found; continuing to compiler validation`);
}
