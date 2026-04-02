import { createServer } from "node:http";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const port = Number.parseInt(process.env.PORT ?? "8787", 10);
const dataFile = process.env.ASTRAL_GLOW_DATA_FILE ?? join(__dirname, "glow-registry-data.json");
const publicListUrl = process.env.ASTRAL_GLOW_PUBLIC_LIST_URL ?? "";
const bootstrapUrl = process.env.ASTRAL_GLOW_BOOTSTRAP_URL ?? "";
const githubRepo = process.env.ASTRAL_GLOW_GITHUB_REPO ?? "";
const githubBranch = process.env.ASTRAL_GLOW_GITHUB_BRANCH ?? "main";
const githubPath = process.env.ASTRAL_GLOW_GITHUB_PATH ?? "glow_list.json";
const githubToken = process.env.ASTRAL_GLOW_GITHUB_TOKEN ?? "";
const defaultNoticeText = "AstralGlow public list. Entries are opt-in and used only for client-side glow cosmetics.";

const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
};

function json(status, body) {
    return {
        status,
        headers: {
            ...corsHeaders,
            "Content-Type": "application/json; charset=utf-8"
        },
        body: JSON.stringify(body, null, 2)
    };
}

function nowIso() {
    return new Date().toISOString();
}

function defaultRegistry() {
    return {
        notice: defaultNoticeText,
        users: [],
        updatedAt: nowIso()
    };
}

function normalizeOptionalString(value) {
    return typeof value === "string" && value.trim().length > 0 ? value : null;
}

function normalizeUser(entry, fallbackSource = "imported") {
    if (validateUuid(entry)) {
        return {
            uuid: entry,
            username: null,
            source: fallbackSource,
            optedInAt: null,
            lastSeenAt: null
        };
    }

    if (!entry || typeof entry !== "object") return null;
    if (!validateUuid(entry.uuid)) return null;

    return {
        uuid: entry.uuid,
        username: validateUsername(entry.username) ? entry.username : null,
        source: normalizeOptionalString(entry.source) ?? fallbackSource,
        optedInAt: normalizeOptionalString(entry.optedInAt),
        lastSeenAt: normalizeOptionalString(entry.lastSeenAt)
    };
}

function mergeUser(existing, incoming) {
    if (incoming.username && !existing.username) existing.username = incoming.username;
    if (incoming.source && !existing.source) existing.source = incoming.source;
    if (incoming.optedInAt && !existing.optedInAt) existing.optedInAt = incoming.optedInAt;
    if (incoming.lastSeenAt) {
        if (!existing.lastSeenAt || incoming.lastSeenAt > existing.lastSeenAt) {
            existing.lastSeenAt = incoming.lastSeenAt;
        }
    }
}

function sortUsers(users) {
    users.sort((a, b) => {
        const usernameCompare = (a.username ?? "").localeCompare(b.username ?? "", undefined, {
            sensitivity: "base"
        });

        if (usernameCompare !== 0) return usernameCompare;
        return a.uuid.localeCompare(b.uuid, undefined, { sensitivity: "base" });
    });
}

function normalizeUsers(entries) {
    const usersByUuid = new Map();

    for (const entry of entries) {
        const normalized = normalizeUser(entry);
        if (!normalized) continue;

        const existing = usersByUuid.get(normalized.uuid);
        if (existing) mergeUser(existing, normalized);
        else usersByUuid.set(normalized.uuid, normalized);
    }

    const users = Array.from(usersByUuid.values());
    sortUsers(users);
    return users;
}

function normalizeRegistry(parsed) {
    if (Array.isArray(parsed)) {
        return {
            notice: defaultNoticeText,
            users: normalizeUsers(parsed),
            updatedAt: nowIso()
        };
    }

    if (!parsed || typeof parsed !== "object") return defaultRegistry();

    return {
        notice: typeof parsed.notice === "string" && parsed.notice.trim().length > 0 ? parsed.notice : defaultNoticeText,
        users: normalizeUsers(Array.isArray(parsed.users) ? parsed.users : []),
        updatedAt: typeof parsed.updatedAt === "string" && parsed.updatedAt.trim().length > 0 ? parsed.updatedAt : nowIso()
    };
}

async function fetchRegistry(url) {
    if (!url) return defaultRegistry();

    const response = await fetch(url, {
        headers: {
            "Accept": "application/json",
            "User-Agent": "astral-glow-registry"
        }
    });

    if (!response.ok) {
        throw new Error(`Registry bootstrap failed with HTTP ${response.status}.`);
    }

    const text = await response.text();
    if (!text.trim()) return defaultRegistry();
    return normalizeRegistry(JSON.parse(text));
}

async function bootstrapRegistry() {
    if (!bootstrapUrl) return defaultRegistry();

    try {
        const registry = await fetchRegistry(bootstrapUrl);
        await saveRegistry(registry);
        return registry;
    } catch (error) {
        console.warn(`Astral glow registry bootstrap failed: ${error instanceof Error ? error.message : String(error)}`);
        return defaultRegistry();
    }
}

async function loadRegistry() {
    try {
        const raw = await readFile(dataFile, "utf8");
        return normalizeRegistry(JSON.parse(raw));
    } catch (error) {
        if (error && (error.code === "ENOENT" || error instanceof SyntaxError)) return bootstrapRegistry();
        throw error;
    }
}

async function saveRegistry(registry) {
    await mkdir(dirname(dataFile), { recursive: true });
    await writeFile(dataFile, JSON.stringify(registry, null, 2), "utf8");
}

function compactRegistry(registry) {
    return registry.users.map(user => user.uuid);
}

function validateUuid(value) {
    return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function validateUsername(value) {
    return typeof value === "string" && /^[A-Za-z0-9_]{1,16}$/.test(value);
}

async function readJsonBody(request) {
    const chunks = [];
    for await (const chunk of request) {
        chunks.push(chunk);
        if (Buffer.concat(chunks).length > 16_384) throw new Error("Request body too large.");
    }

    const raw = Buffer.concat(chunks).toString("utf8");
    return raw ? JSON.parse(raw) : {};
}

function upsertUser(registry, payload) {
    const existing = registry.users.find(user => user.uuid === payload.uuid);
    const timestamp = nowIso();

    if (existing) {
        existing.username = payload.username;
        if (!existing.optedInAt) existing.optedInAt = timestamp;
        existing.lastSeenAt = timestamp;
        existing.source = "share-presence";
        return { added: false, user: existing };
    }

    const user = {
        uuid: payload.uuid,
        username: payload.username,
        source: "share-presence",
        optedInAt: timestamp,
        lastSeenAt: timestamp
    };

    registry.users.push(user);
    sortUsers(registry.users);
    return { added: true, user };
}

async function mirrorToGitHub(registry) {
    if (!githubRepo || !githubToken) {
        return { githubMirrored: null, message: "GitHub mirroring is not configured." };
    }

    const contentUrl = `https://api.github.com/repos/${githubRepo}/contents/${githubPath}?ref=${encodeURIComponent(githubBranch)}`;
    const headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": `Bearer ${githubToken}`,
        "User-Agent": "astral-glow-registry"
    };

    let sha;
    const current = await fetch(contentUrl, { headers });
    if (current.ok) {
        const currentJson = await current.json();
        sha = currentJson.sha;
    } else if (current.status !== 404) {
        throw new Error(`GitHub contents lookup failed with HTTP ${current.status}.`);
    }

    const body = {
        message: `Update Astral glow registry (${registry.users.length} users)`,
        content: Buffer.from(JSON.stringify(registry, null, 2), "utf8").toString("base64"),
        branch: githubBranch
    };

    if (sha) body.sha = sha;

    const update = await fetch(`https://api.github.com/repos/${githubRepo}/contents/${githubPath}`, {
        method: "PUT",
        headers: {
            ...headers,
            "Content-Type": "application/json"
        },
        body: JSON.stringify(body)
    });

    if (!update.ok) {
        const text = await update.text();
        throw new Error(`GitHub mirror update failed with HTTP ${update.status}: ${text}`);
    }

    return {
        githubMirrored: true,
        message: `GitHub mirror updated at ${githubRepo}/${githubPath}.`
    };
}

async function handleGetList(compact = false) {
    const registry = await loadRegistry();
    return json(200, compact ? compactRegistry(registry) : registry);
}

async function handleShare(request) {
    const payload = await readJsonBody(request);
    if (!validateUuid(payload.uuid)) {
        return json(400, { ok: false, message: "Expected a valid UUID string." });
    }

    if (!validateUsername(payload.username)) {
        return json(400, { ok: false, message: "Expected a valid Minecraft username." });
    }

    const registry = await loadRegistry();
    const result = upsertUser(registry, payload);
    registry.updatedAt = nowIso();
    await saveRegistry(registry);

    let mirror = {
        githubMirrored: null,
        message: "GitHub mirroring is not configured."
    };

    try {
        mirror = await mirrorToGitHub(registry);
    } catch (error) {
        mirror = {
            githubMirrored: false,
            message: error instanceof Error ? error.message : String(error)
        };
    }

    return json(200, {
        ok: true,
        added: result.added,
        sharedFields: ["uuid", "username"],
        publicListUrl,
        githubMirrored: mirror.githubMirrored,
        userCount: registry.users.length,
        message: `${result.added ? "Presence stored." : "Presence refreshed."} ${mirror.message}`.trim()
    });
}

const server = createServer(async (request, response) => {
    try {
        if (!request.url) {
            const result = json(400, { ok: false, message: "Missing request URL." });
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        const url = new URL(request.url, `http://${request.headers.host ?? "localhost"}`);

        if (request.method === "OPTIONS") {
            response.writeHead(204, corsHeaders);
            response.end();
            return;
        }

        if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
            const result = json(200, {
                ok: true,
                service: "astral-glow-registry",
                publicListUrl,
                bootstrapUrl,
                githubMirrorConfigured: Boolean(githubRepo && githubToken)
            });
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        if (request.method === "GET" && (url.pathname === "/glow_list.json" || url.pathname === "/glow-list")) {
            const result = await handleGetList(false);
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        if (request.method === "GET" && url.pathname === "/glow-list/compact") {
            const result = await handleGetList(true);
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        if (request.method === "POST" && url.pathname === "/share") {
            const result = await handleShare(request);
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        const result = json(404, { ok: false, message: "Not found." });
        response.writeHead(result.status, result.headers);
        response.end(result.body);
    } catch (error) {
        const result = json(500, {
            ok: false,
            message: error instanceof Error ? error.message : String(error)
        });
        response.writeHead(result.status, result.headers);
        response.end(result.body);
    }
});

server.listen(port, () => {
    console.log(`Astral glow registry listening on http://localhost:${port}`);
});
