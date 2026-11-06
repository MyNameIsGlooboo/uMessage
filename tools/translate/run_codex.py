"""Send the prepared prompts to ChatGPT through the local Codex CLI and save the answers.

Same mechanism as the /chatgpt skill: `codex exec` with the prompt on stdin, read-only sandbox, so Codex never has to
read a file. Standard library only.

  python tools/translate/run_codex.py glossary --lang es fr
  python tools/translate/run_codex.py batches --lang es --workers 3
"""
import argparse
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
GLOSSARY = os.path.join(HERE, "glossary")
CODEX = os.path.join(os.environ.get("APPDATA", ""), "npm", "codex.cmd")
MODEL = "gpt-6.1-sol"
EFFORT = "medium"
TIMEOUT = 1500
TRIES = 3


def ask(prompt):
    """One ChatGPT answer for [prompt]; raises RuntimeError after TRIES failures."""
    last = ""
    for attempt in range(1, TRIES + 1):
        out = tempfile.NamedTemporaryFile(delete=False, suffix=".txt")
        out.close()
        try:
            run = subprocess.run(
                [CODEX, "exec", "--skip-git-repo-check", "-m", MODEL, "-c", f'model_reasoning_effort="{EFFORT}"',
                 "-s", "read-only", "-o", out.name, "-"],
                input=prompt.encode("utf-8"), capture_output=True, timeout=TIMEOUT, cwd=HERE,
            )
            with open(out.name, "rb") as handle:
                answer = handle.read().decode("utf-8", "replace").strip()
            if run.returncode == 0 and answer:
                return answer
            last = (run.stderr or b"").decode("utf-8", "replace")[-400:] or f"exit {run.returncode}, empty answer"
        except subprocess.TimeoutExpired:
            last = f"timeout after {TIMEOUT}s"
        finally:
            os.unlink(out.name)
        time.sleep(10 * attempt)
    raise RuntimeError(last)


def json_of(text):
    """The first JSON object in [text], tolerating a markdown fence around it."""
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S)
    body = fenced.group(1) if fenced else text[text.index("{"):text.rindex("}") + 1]
    return json.loads(body)


def glossary(lang):
    request = os.path.join(WORK, lang, "glossary_request.txt")
    with open(request, encoding="utf-8") as handle:
        data = json_of(ask(handle.read()))
    os.makedirs(GLOSSARY, exist_ok=True)
    with open(os.path.join(GLOSSARY, lang + ".json"), "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
    return f"{lang}: glossary {len(data)} terms"


def batch(lang, name):
    prompt_file = os.path.join(WORK, lang, "prompt", name + ".txt")
    done = os.path.join(WORK, lang, "done")
    os.makedirs(done, exist_ok=True)
    target = os.path.join(done, name + ".json")
    if os.path.exists(target):
        return f"{lang} {name}: kept"
    with open(prompt_file, encoding="utf-8") as handle:
        data = json_of(ask(handle.read()))
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=1)
    return f"{lang} {name}: {len(data)} units"


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("what", choices=["glossary", "batches"])
    parser.add_argument("--lang", nargs="+", required=True)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--only", nargs="*", help="batch names such as batch_01")
    args = parser.parse_args()
    jobs = []
    for lang in args.lang:
        if args.what == "glossary":
            jobs.append((glossary, (lang,)))
        else:
            names = args.only or sorted(f[:-4] for f in os.listdir(os.path.join(WORK, lang, "prompt")))
            jobs.extend((batch, (lang, n)) for n in names)
    failed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fn, *a): a for fn, a in jobs}
        for future in concurrent.futures.as_completed(futures):
            try:
                print(future.result(), flush=True)
            except Exception as error:  # noqa: BLE001 - report and go on with the other jobs
                failed += 1
                print("FAILED", futures[future], str(error)[:300], flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
