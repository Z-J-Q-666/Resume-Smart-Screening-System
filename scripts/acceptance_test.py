"""验收清单自动核对（对应需求 §11.1 的 10 条）。

用法（后端 + AI 服务 + MQ 全部启动后）：
    conda activate ai
    python scripts/acceptance_test.py            # 跑第 1-8、10 条

第 9 条（Python 停机后上传仍可恢复）需要手动停服务，脚本不自动执行，
跑法见 README 的「验收」章节。
"""

import json
import random
import string
import subprocess
import sys
import time
from pathlib import Path

import requests

API = "http://127.0.0.1:8080/api"
REDIS_CLI = "C:/tools/redis-win/redis-cli.exe"
OUT_DIR = Path(__file__).resolve().parent.parent / "demo" / "acceptance"

results = []


def check(no, title, passed, detail=""):
    results.append((no, title, passed, detail))
    print(f"[{'PASS' if passed else 'FAIL'}] {no}. {title}" + (f" — {detail}" if detail else ""))
    return passed


# --------------------------------------------------------------------- 测试数据

def make_pdf(tag: str, scanned: bool = False) -> Path:
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import mm
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfgen import canvas
    from reportlab.platypus import Paragraph, SimpleDocTemplate
    from reportlab.lib.styles import ParagraphStyle

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    pdfmetrics.registerFont(TTFont("SimHei", "C:/Windows/Fonts/simhei.ttf"))
    path = OUT_DIR / (f"acc_{tag}_scan.pdf" if scanned else f"acc_{tag}.pdf")

    if scanned:
        c = canvas.Canvas(str(path), pagesize=A4)
        c.setFillGray(0.9)
        c.rect(40, 40, A4[0] - 80, A4[1] - 80, stroke=0, fill=1)
        c.showPage()
        c.save()
        return path

    style = ParagraphStyle("b", fontName="SimHei", fontSize=10, leading=16)
    title = ParagraphStyle("t", fontName="SimHei", fontSize=18, leading=24)
    doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm,
                            topMargin=18 * mm, bottomMargin=18 * mm)
    story = [
        Paragraph("验收测试简历", title),
        Paragraph(f"编号：{tag}", style),
        Paragraph("姓名：测试候选人", style),
        Paragraph("电话：13900000000　邮箱：test@example.com", style),
        Paragraph("教育背景：2016.09-2020.06　测试大学　软件工程　本科", style),
        Paragraph("工作经历：2020.07-至今　测试科技有限公司　Java 开发工程师", style),
        Paragraph("负责交易系统后端开发，使用 SpringBoot + MyBatis，MySQL 存储，Redis 缓存，"
                  "RocketMQ 做异步削峰。", style),
        Paragraph("技能：Java、SpringBoot、MyBatis、MySQL、Redis、RocketMQ、Git、Linux", style),
    ]
    doc.build(story)
    return path


def new_job_name():
    return "验收岗位-" + "".join(random.choices(string.ascii_uppercase + string.digits, k=6))


def wait_status(resume_id, timeout=60, want="EXTRACTED"):
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout:
        r = requests.get(f"{API}/resumes/{resume_id}/status", timeout=5).json()["data"]
        last = r["status"]
        if last == want:
            return last, time.time() - t0
        if last == "FAILED" and want != "FAILED":
            return last, time.time() - t0
        time.sleep(0.8)
    return last, time.time() - t0


def wait_application(app_id, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        d = requests.get(f"{API}/applications/{app_id}", timeout=10).json()["data"]
        if d["status"] in ("SCORED", "FAILED"):
            return d
        time.sleep(1.2)
    return requests.get(f"{API}/applications/{app_id}", timeout=10).json()["data"]


# ------------------------------------------------------------------------- 用例

def main():
    tag = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))

    # 前置：建岗位
    job = {
        "title": new_job_name(),
        "department": "验收",
        "educationReq": "本科",
        "expYearsReq": 3,
        "mustSkills": ["Java", "SpringBoot", "MySQL", "RocketMQ"],
        "plusSkills": ["Redis"],
        "jdText": "负责交易系统后端开发，要求熟悉 Java、Spring Boot、MySQL、RocketMQ，"
                  "有高并发与消息队列削峰经验。",
    }
    job_id = requests.post(f"{API}/jobs", json=job, timeout=10).json()["data"]["jobId"]
    print(f"测试岗位 jobId={job_id} {job['title']}\n")

    text_pdf = make_pdf(tag)
    scan_pdf = make_pdf(tag, scanned=True)

    # 1. 上传 PDF：200 + resumeId，<500ms
    t0 = time.time()
    with open(text_pdf, "rb") as f:
        resp = requests.post(f"{API}/resumes", files={"file": (text_pdf.name, f, "application/pdf")},
                             timeout=30)
    elapsed = (time.time() - t0) * 1000
    body = resp.json()
    ok1 = resp.status_code == 200 and body["code"] == 0 and body["data"]["resumeId"] and elapsed < 500
    resume_id = body["data"]["resumeId"] if body.get("data") else None
    check(1, "上传 PDF 返回 resumeId 且耗时 < 500ms", ok1, f"HTTP {resp.status_code}, {elapsed:.0f}ms")

    # 4. 图片型 PDF → FAILED + 未检测到文本层（先投，后面一起等）
    with open(scan_pdf, "rb") as f:
        scan_body = requests.post(f"{API}/resumes",
                                  files={"file": (scan_pdf.name, f, "application/pdf")},
                                  timeout=30).json()
    scan_id = scan_body["data"]["resumeId"]

    # 2. 重复上传同一文件 → duplicated=true，不新增记录
    with open(text_pdf, "rb") as f:
        dup = requests.post(f"{API}/resumes",
                            files={"file": (text_pdf.name, f, "application/pdf")}, timeout=30).json()
    ok2 = dup["data"]["duplicated"] is True and dup["data"]["resumeId"] == resume_id
    check(2, "重复上传去重，不新增记录", ok2,
          f"duplicated={dup['data']['duplicated']} resumeId={dup['data']['resumeId']}")

    # 3. 解析完成 → EXTRACTED 且字段齐全
    status, cost = wait_status(resume_id)
    detail = requests.get(f"{API}/resumes/{resume_id}", timeout=10).json()["data"]
    profile = detail.get("profile") or {}
    has_fields = all(k in profile for k in ("name", "education", "skills", "total_years"))
    ok3 = status == "EXTRACTED" and cost < 15 and has_fields and bool(profile.get("skills"))
    check(3, "15s 内解析完成且字段齐全", ok3,
          f"status={status}, {cost:.1f}s, total_years={profile.get('total_years')}, "
          f"skills={len(profile.get('skills') or [])}")

    # 4. 图片型 / 无文本层 → FAILED 且提示明确
    scan_status, _ = wait_status(scan_id, timeout=40, want="FAILED")
    scan_detail = requests.get(f"{API}/resumes/{scan_id}", timeout=10).json()["data"]
    ok4 = scan_status == "FAILED" and "文本层" in (scan_detail.get("errorMsg") or "")
    check(4, "无文本层 PDF 判定失败并提示", ok4, f"status={scan_status}, err={scan_detail.get('errorMsg')}")

    # 5. 未解析完成就投递 → 409 / code 1003
    r5 = requests.post(f"{API}/applications", json={"jobId": job_id, "resumeId": scan_id}, timeout=10)
    ok5 = r5.status_code == 409 and r5.json()["code"] == 1003
    check(5, "未解析完成投递返回 409/1003", ok5, f"HTTP {r5.status_code}, code={r5.json()['code']}")

    # 6. 打分结果：分项相加、综合分公式、命中/缺失技能对得上
    app = requests.post(f"{API}/applications", json={"jobId": job_id, "resumeId": resume_id},
                        timeout=10).json()["data"]
    app_id = app["applicationId"]
    detail6 = wait_application(app_id)
    b = detail6.get("breakdown") or {}
    sub_sum = b.get("educationScore", 0) + b.get("expScore", 0) + b.get("skillScore", 0)
    rule_match = sub_sum == b.get("ruleScore")
    total = detail6.get("score") or 0
    expect = round(b.get("ruleScore", 0) * 0.6 + (b.get("semanticScore") or 0) * 0.4, 2)
    formula_ok = abs(float(total) - expect) < 0.02
    hit, miss = set(detail6.get("hitSkills") or []), set(detail6.get("missSkills") or [])
    must_norm = {s.lower().replace(" ", "") for s in job["mustSkills"]}
    union_norm = {s.lower().replace(" ", "") for s in (hit | miss)}
    skills_ok = union_norm == must_norm and not (hit & miss)
    ok6 = detail6["status"] == "SCORED" and rule_match and formula_ok and skills_ok
    check(6, "分项相加=规则分、加权=综合分、命中/缺失技能对齐", ok6,
          f"分项合={sub_sum}/规则={b.get('ruleScore')}, 综合={total}/期望={expect}, "
          f"命中={sorted(hit)}, 缺失={sorted(miss)}")

    # 7. 重复投递 → 不新增记录，分数被覆盖更新
    first_score = detail6.get("score")
    app2 = requests.post(f"{API}/applications", json={"jobId": job_id, "resumeId": resume_id},
                         timeout=10).json()["data"]
    detail7 = wait_application(app2["applicationId"])
    board = requests.get(f"{API}/jobs/{job_id}/applications",
                         params={"page": 1, "size": 50}, timeout=10).json()["data"]
    ok7 = app2["applicationId"] == app_id and board["total"] == 1 and detail7["status"] == "SCORED"
    check(7, "重复投递不新增记录，分数被覆盖", ok7,
          f"applicationId={app2['applicationId']}（原 {app_id}），"
          f"看板记录数={board['total']}，{first_score} → {detail7.get('score')}")

    # 8. 看板排序：按综合分倒序，未打分排最后
    #    再投 2 份不同简历，让排序有可比性
    extra = []
    for _ in range(2):
        p = make_pdf(tag + "".join(random.choices(string.ascii_lowercase, k=4)))
        with open(p, "rb") as f:
            rid = requests.post(f"{API}/resumes",
                                files={"file": (p.name, f, "application/pdf")},
                                timeout=30).json()["data"]["resumeId"]
        wait_status(rid, timeout=60)
        a = requests.post(f"{API}/applications", json={"jobId": job_id, "resumeId": rid},
                          timeout=10).json()["data"]
        extra.append(a["applicationId"])
    for a in extra:
        wait_application(a)
    board8 = requests.get(f"{API}/jobs/{job_id}/applications",
                          params={"page": 1, "size": 50}, timeout=10).json()["data"]
    scores = [r["score"] for r in board8["records"]]
    non_null = [s for s in scores if s is not None]
    ok8 = non_null == sorted(non_null, reverse=True) and scores[:len(non_null)] == non_null
    check(8, "看板按综合分倒序，未打分排最后", ok8, f"scores={scores}")

    # 10. Redis 清空后功能正常（仅第一次轮询变慢）
    try:
        subprocess.run([REDIS_CLI, "-p", "6379", "FLUSHDB"], capture_output=True, timeout=5)
        s1 = requests.get(f"{API}/resumes/{resume_id}/status", timeout=5).json()["data"]
        b1 = requests.get(f"{API}/jobs/{job_id}/applications",
                          params={"page": 1, "size": 10}, timeout=5).json()["data"]
        t1 = requests.get(f"{API}/jobs/{job_id}/top", params={"n": 5}, timeout=5).json()["data"]
        ok10 = s1["status"] == "EXTRACTED" and b1["total"] == 3 and len(t1) == 3
        check(10, "Redis 清空后功能正常，排行榜可回源重建", ok10,
              f"status={s1['status']}, 看板={b1['total']}, TOP={len(t1)}, "
              f"source={t1[0]['source'] if t1 else '-'}")
    except Exception as e:
        check(10, "Redis 清空后功能正常", False, f"执行失败：{e}")

    # 汇总
    print("\n" + "=" * 68)
    passed = sum(1 for r in results if r[2])
    print(f"通过 {passed}/{len(results)} 条（第 9 条需手动停 Python 服务，见 README）")
    if passed < len(results):
        print("未通过项：")
        for no, title, ok, detail in results:
            if not ok:
                print(f"  - {no}. {title}：{detail}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
