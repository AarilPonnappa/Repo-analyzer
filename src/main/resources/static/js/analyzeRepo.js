const form = document.getElementById("form");
const details = document.getElementById("details");
const tokenField = document.getElementById("tokenField");
const tokenInput = document.getElementById("token");
const messageEl = document.getElementById("message");
const submitBtn = document.getElementById("submit");

// Open the dropdown when public/private is picked, and show the token field only for private
document.querySelectorAll('input[name="visibility"]').forEach(radio => {
    radio.addEventListener("change", () => {
        details.classList.add("open");
        tokenField.classList.toggle("hidden", radio.value !== "private");
        showMessage("");
        setTimeout(() => form.elements.repo.focus(), 300);
    });
});

// Show / hide the token
document.getElementById("toggleToken").addEventListener("click", e => {
    const hidden = tokenInput.type === "password";
    tokenInput.type = hidden ? "text" : "password";
    e.target.textContent = hidden ? "Hide" : "Show";
});

// /checkoutrepo?repo=owner/repo (from a share link for a repo that wasn't analyzed yet) fills in the form
const presetRepo = new URLSearchParams(location.search).get("repo");
if (presetRepo) {
    const publicRadio = document.getElementById("public");
    publicRadio.checked = true;
    publicRadio.dispatchEvent(new Event("change"));
    form.elements.repo.value = presetRepo;
}

// Turn "https://github.com/owner/repo/tree/main" or "owner/repo.git" into "owner/repo"
function parseRepo(value) {
    const v = value.trim()
        .replace(/^https?:\/\//, "")
        .replace(/^(www\.)?github\.com\//, "")
        .replace(/\/+$/, "");
    const parts = v.split("/");
    if (parts.length < 2) return null;
    const owner = parts[0];
    const repo = parts[1].replace(/\.git$/, "");
    const valid = /^[A-Za-z0-9-_.]+$/;
    return valid.test(owner) && valid.test(repo) ? `${owner}/${repo}` : null;
}

// A URL like .../tree/develop also tells us the branch
function branchFromUrl(value) {
    const m = value.trim().match(/github\.com\/[^/]+\/[^/]+\/tree\/([^?#]+)/);
    return m ? decodeURIComponent(m[1]).replace(/\/+$/, "") : "";
}

// type is "error" or "success"
function showMessage(text, type = "error") {
    messageEl.textContent = text;
    messageEl.className = "message " + type;
}

function setLoading(loading) {
    submitBtn.disabled = loading;
    submitBtn.textContent = loading ? "Checking…" : "Analyze";
}

async function AnalyzeRepo() {
    showMessage("");

    // public, private, or null if nothing picked
    const selected = document.querySelector('input[name="visibility"]:checked');
    const repoChoice = selected ? selected.value : null;

    const repo = parseRepo(form.elements.repo.value);
    // Empty means "the repo's default branch"; the server looks it up
    const branch = form.elements.branch.value.trim() || branchFromUrl(form.elements.repo.value);
    const token = form.elements.token.value.trim();

    // Check the inputs before calling the backend
    if (!repoChoice) {
        showMessage("Choose public or private.");
        return;
    }
    if (!repo) {
        showMessage("Enter a valid repo, like owner/repo or a GitHub URL.");
        return;
    }
    if (repoChoice === "private" && !token) {
        showMessage("A private repo needs a personal access token.");
        return;
    }

    const data = {
        visibility: repoChoice,
        repo: repo,
        branch: branch,
        token: repoChoice === "private" ? token : null
    };

    setLoading(true);

    try {
        const response = await fetch("/api/analyses", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data)
        });

        // The backend sends JSON for both success and errors
        const resultData = await response.json().catch(() => ({}));

        if (!response.ok) {
            throw new Error(resultData.error || "Failed to analyze repository.");
        }

        showMessage(resultData.cached ? "Already analyzed. Opening the report…" : "Started. Opening the live progress…", "success");

        // The analysis runs in the background; the result page follows it live.
        // Private repos get a key that's needed to open their results.
        let url = `/result?id=${encodeURIComponent(resultData.id)}`;
        if (resultData.key) url += `&key=${encodeURIComponent(resultData.key)}`;
        window.location.href = url;
    } catch (error) {
        console.error(error);
        showMessage(error.message);
        setLoading(false);
    }
}

// Recently analyzed public repos
function timeAgo(iso) {
    if (!iso) return "";
    const s = (Date.now() - new Date(iso).getTime()) / 1000;
    if (s < 60) return "just now";
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return `${Math.round(s / 86400)} d ago`;
}

function renderRecent(list) {
    const ul = document.getElementById("recent");
    ul.innerHTML = "";
    if (!list.length) {
        const li = document.createElement("li");
        li.className = "empty";
        li.textContent = "No repos analyzed yet. Try spring-projects/spring-petclinic.";
        ul.appendChild(li);
        return;
    }
    list.forEach(r => {
        const li = document.createElement("li");
        const a = document.createElement("a");
        a.href = `/result?id=${encodeURIComponent(r.id)}`;

        const left = document.createElement("div");
        const name = document.createElement("div");
        name.className = "repo-name";
        name.textContent = r.repo;
        const meta = document.createElement("div");
        meta.className = "repo-meta";
        meta.textContent = `${r.branch} · ${timeAgo(r.analyzedAt)}`;
        left.append(name, meta);

        const grade = document.createElement("span");
        grade.className = "grade";
        grade.textContent = r.grade || "–";

        a.append(left, grade);
        li.appendChild(a);
        ul.appendChild(li);
    });
}

async function loadRecent() {
    try {
        const res = await fetch("/api/analyses/recent");
        if (!res.ok) return;
        renderRecent(await res.json());
    } catch {
        // keep the default text
    }
}

loadRecent();
