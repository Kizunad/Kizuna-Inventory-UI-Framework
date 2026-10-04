#!/usr/bin/env python3
"""检查 Wiki 文档，并将主仓库中的受管页面发布到 GitHub Wiki。"""

import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
PAGES = ROOT / "docs" / "wiki"
WIKI_REMOTE = "https://github.com/Kizunad/Kizuna-Inventory-UI-Framework.wiki.git"
WIKI_NEW_PAGE = "https://github.com/Kizunad/Kizuna-Inventory-UI-Framework/wiki/_new"
REQUIRED_PAGES = {
    "Home", "Getting-Started", "Modules-and-Windows", "Inventory-and-State",
    "HUD-and-Slot-Bars", "Themes-and-Backgrounds", "Server-Communication",
    "Troubleshooting", "Versioning-and-Compatibility", "Documentation-Maintenance",
    "_Sidebar", "_Footer",
}
LINK = re.compile(r"\]\(([^\s)]+)\)")


def git(directory: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(directory), *args],
        text=True,
        capture_output=True,
        check=True,
    )
    return result.stdout.strip()


def check() -> list[Path]:
    pages = sorted(PAGES.glob("*.md"))
    missing = REQUIRED_PAGES - {path.stem for path in pages}
    if missing:
        raise ValueError(f"缺少 Wiki 页面：{', '.join(sorted(missing))}")
    links = 0
    for page in pages:
        if not re.fullmatch(r"(?:[A-Z][A-Za-z0-9-]*|_Sidebar|_Footer)", page.stem):
            raise ValueError(f"页面名称须为稳定的英文标识：{page.name}")
        content = page.read_text(encoding="utf-8")
        if not content.strip():
            raise ValueError(f"空页面：{page.name}")
        if len(re.findall(r"^```", content, re.MULTILINE)) % 2:
            raise ValueError(f"代码块未闭合：{page.name}")
        for target in LINK.findall(content):
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            destination = (page.parent / unquote(parsed.path)).resolve()
            if destination.parent != PAGES.resolve() or destination not in pages:
                raise ValueError(
                    f"无效 Wiki 页面链接：{page.name} -> {target}；"
                    "跨出 Wiki 的文档请使用完整仓库链接"
                )
            if parsed.query:
                raise ValueError(f"Wiki 页面链接不支持 query：{target}")
            links += 1
    print(f"Wiki 检查通过：{len(pages)} 个页面，{links} 个内部链接。")
    return pages


def render(content: str) -> str:
    def wiki_link(match: re.Match) -> str:
        target = match.group(1)
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc or not parsed.path.endswith(".md"):
            return match.group(0)
        destination = parsed.path[:-3]
        if parsed.fragment:
            destination += "#" + parsed.fragment
        return f"]({destination})"

    return LINK.sub(wiki_link, content)


def publish(pages: list[Path]) -> None:
    if git(ROOT, "status", "--porcelain"):
        raise ValueError("请先提交主仓库改动，再发布对应版本的 Wiki。")
    source_commit = git(ROOT, "rev-parse", "--short", "HEAD")
    with tempfile.TemporaryDirectory(prefix="inventory-ui-wiki-") as temporary:
        checkout = Path(temporary) / "wiki"
        try:
            subprocess.run(
                ["git", "clone", WIKI_REMOTE, str(checkout)],
                text=True, capture_output=True, check=True,
            )
        except subprocess.CalledProcessError as failure:
            raise ValueError(
                "无法克隆 Wiki。请检查网络和 Git 写权限；如果这是首次发布，"
                f"请在 {WIKI_NEW_PAGE} 创建 Home 页面后重试。\n"
                + failure.stderr.strip()
            ) from failure

        if not git(checkout, "branch", "--show-current"):
            raise ValueError("Wiki 未检出有效分支，停止发布。")
        for page in pages:
            target = checkout / page.name
            if target.is_symlink():
                raise ValueError(f"受管页面不能是符号链接：{page.name}")
            target.write_text(render(page.read_text(encoding="utf-8")), encoding="utf-8")
        git(checkout, "add", "--", *[page.name for page in pages])
        if not git(checkout, "diff", "--cached", "--name-only"):
            print("Wiki 已与文档源一致，无需提交。")
            return
        git(checkout, "diff", "--cached", "--check")
        # 继承调用者的提交身份及签名配置，不绕过 Git hooks。
        for key in ("user.name", "user.email", "user.signingkey", "commit.gpgsign", "gpg.format"):
            try:
                value = git(ROOT, "config", "--get", key)
            except subprocess.CalledProcessError:
                continue
            git(checkout, "config", key, value)
        git(
            checkout, "commit",
            "-m", f"同步接入与扩展文档（源提交 {source_commit}）",
            "-m", "Documentation-Source: " + git(ROOT, "rev-parse", "HEAD"),
        )
        git(checkout, "push", "origin", "HEAD")
        print(f"Wiki 发布完成：{git(checkout, 'rev-parse', '--short', 'HEAD')}。")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "publish"))
    args = parser.parse_args()
    try:
        pages = check()
        if args.command == "publish":
            publish(pages)
    except (ValueError, OSError, subprocess.CalledProcessError) as failure:
        print(f"错误：{failure}", file=sys.stderr)
        if isinstance(failure, subprocess.CalledProcessError) and failure.stderr:
            print(failure.stderr.strip(), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
