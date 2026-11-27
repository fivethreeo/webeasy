import encoded from "./generated/renderer-bytes.js";

const unavailable = () => { throw new Error("The template engine cannot access the filesystem or process."); };
let instance;
function engineInstance() {
  if (!instance) {
    const bytes = Uint8Array.from(atob(encoded), (character) => character.charCodeAt(0));
    const module = new WebAssembly.Module(bytes);
    instance = new WebAssembly.Instance(module, {
      wasi_snapshot_preview1: {
        proc_exit: unavailable, fd_close: unavailable, fd_seek: unavailable, fd_write: unavailable,
        fd_read: unavailable, clock_time_get: unavailable,
      },
      env: { emscripten_notify_memory_growth() {}, __syscall_unlinkat: unavailable },
    }).exports;
    instance._initialize?.();
  }
  return instance;
}
const encoder = new TextEncoder();
const decoder = new TextDecoder();

// Every boundary uses the same ownership rule: malloc input, call C, decode
// returned JSON, then release both buffers even if JSON/error handling throws.
function invoke(request, operation) {
  const engine = engineInstance();
  const input = encoder.encode(JSON.stringify(request) + "\0");
  const pointer = engine.malloc(input.length);
  if (!pointer) throw new Error("Cannot allocate template input.");
  let result = 0;
  try {
    new Uint8Array(engine.memory.buffer, pointer, input.length).set(input);
    result = engine[operation](pointer);
    if (!result) throw new Error("Cannot allocate template output.");
    const memory = new Uint8Array(engine.memory.buffer);
    const end = memory.indexOf(0, result);
    if (end < 0) throw new Error("Invalid template output.");
    const output = JSON.parse(decoder.decode(memory.subarray(result, end)));
    if (output.error) throw new Error(output.error);
    return output;
  } finally {
    engine.webeasy_free(result);
    engine.webeasy_free(pointer);
  }
}

export function renderRequest(request) {
  const output = invoke(request, "webeasy_render");
  if (!Array.isArray(output.files)) throw new Error("Invalid template output.");
  return output.files;
}

export const normalizeRequest = (project) => invoke(project, "webeasy_normalize");
export const prepareRequest = (input) => invoke(input, "webeasy_prepare");
export const compileRequest = (input) => invoke(input, "webeasy_compile");
