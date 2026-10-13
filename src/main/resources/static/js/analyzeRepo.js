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
    const branch = form.elements.branch.value.trim() || "main";
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

        console.log(resultData);
        showMessage(
            `Found ${resultData.repo} (${resultData.language}, ${resultData.size} KB) on branch ${resultData.branch}.`,
            "success"
        );

        // Once the backend returns a job id, replace the message above with:
        // window.location.href = `/result?id=${encodeURIComponent(resultData.id)}`;
    } catch (error) {
        console.error(error);
        showMessage(error.message);
    } finally {
        setLoading(false);
    }
}

// Recently analyzed repos
// function renderRecent(list) {
//     const ul = document.getElementById("recent");
//     ul.innerHTML = "";
//     if (!list.length) {
//         ul.innerHTML = '<li class="empty">No recent analyses.</li>';
//         return;
//     }
//     list.forEach(r => {
//         const li = document.createElement("li");
//         const a = document.createElement("a");
//         a.href = `/result?id=${encodeURIComponent(r.id)}`;

//         const left = document.createElement("div");
//         const name = document.createElement("div");
//         name.className = "repo-name";
//         name.textContent = r.repo;
//         const meta = document.createElement("div");
//         meta.className = "repo-meta";
//         meta.textContent = `${r.branch} · ${r.analyzedAt}`;
//         left.append(name, meta);

//         const grade = document.createElement("span");
//         grade.className = "grade";
//         grade.textContent = r.grade;

//         a.append(left, grade);
//         li.appendChild(a);
//         ul.appendChild(li);
//     });
//}

// async function loadRecent() {
//     try {
//         const res = await fetch("/api/analyses/recent");
//         if (!res.ok) return;          // endpoint doesn't exist yet, keep the default text
//         renderRecent(await res.json());
//     } catch {
//         // keep the default text
//     }
// }

// loadRecent();