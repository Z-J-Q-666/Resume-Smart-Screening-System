#!/usr/bin/env python
"""语义分区分度检查。

为什么需要这个脚本：
    模型对"同档内强弱"不敏感时，多个候选人的语义分会挤在一起
    （实测曾出现 38/38/42/42 —— 分数看着正常，排序等于没排）。
    这个检查把"区分度"变成可断言的指标，改完 prompt 跑一次就知道有没有退化。

用法：
    python scripts/check_dispersion.py
    python scripts/check_dispersion.py --min-std 5 --min-samples 3
    python scripts/check_dispersion.py --job 1

退出码：
    0  全部通过
    1  有岗位区分度不足，或没有数据
"""

import argparse
import os
import statistics
import sys

import pymysql

# 与 application.yml 的 app.score.match-*-threshold 保持一致
HIGH = 70
PARTIAL = 50


def fetch_rows(conn, job_id=None):
    sql = """
        SELECT ja.job_id, j.title, ja.semantic_score
          FROM job_application ja
          JOIN job j ON j.id = ja.job_id
         WHERE ja.semantic_score IS NOT NULL
    """
    params = []
    if job_id is not None:
        sql += " AND ja.job_id = %s"
        params.append(job_id)
    sql += " ORDER BY ja.job_id, ja.id"
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def bucket(score):
    if score >= HIGH:
        return "HIGH"
    if score >= PARTIAL:
        return "PARTIAL"
    return "NONE"


def main():
    ap = argparse.ArgumentParser(description="检查同一岗位下语义分的区分度")
    ap.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "3306")))
    ap.add_argument("--user", default=os.getenv("DB_USER", "root"))
    ap.add_argument("--password", default=os.getenv("DB_PASSWORD", "123456"))
    ap.add_argument("--database", default=os.getenv("DB_NAME", "resume_screening"))
    ap.add_argument("--min-std", type=float, default=5.0,
                    help="标准差下限，低于则告警（默认 5.0）")
    ap.add_argument("--min-samples", type=int, default=3,
                    help="样本数低于此值不做判定（默认 3）")
    ap.add_argument("--job", type=int, default=None, help="只检查指定岗位")
    args = ap.parse_args()

    try:
        conn = pymysql.connect(
            host=args.host, port=args.port, user=args.user,
            password=args.password, database=args.database, charset="utf8mb4",
        )
    except Exception as e:
        print(f"连接数据库失败：{e}")
        return 1

    try:
        rows = fetch_rows(conn, args.job)
    finally:
        conn.close()

    if not rows:
        print("没有已打分的投递记录，先跑一遍打分再检查。")
        return 1

    grouped = {}
    for job_id, title, score in rows:
        grouped.setdefault((job_id, title), []).append(float(score))

    failures = []
    print(f"{'岗位':<5} {'样本':>5} {'均值':>7} {'σ':>6} {'极差':>5}  分档(H/P/N)")
    print("-" * 74)
    for (job_id, title), scores in sorted(grouped.items()):
        n = len(scores)
        mean = statistics.fmean(scores)
        std = statistics.pstdev(scores) if n > 1 else 0.0
        spread = max(scores) - min(scores)
        buckets = {"HIGH": 0, "PARTIAL": 0, "NONE": 0}
        for s in scores:
            buckets[bucket(s)] += 1
        dist = f"{buckets['HIGH']}/{buckets['PARTIAL']}/{buckets['NONE']}"

        flag = ""
        if n >= args.min_samples and std < args.min_std:
            flag = "   <-- 区分度不足"
            failures.append((job_id, title, n, std))
        print(f"{job_id:<5} {n:>5} {mean:>7.1f} {std:>6.1f} {spread:>5.1f}  {dist}{flag}")

    print()
    if failures:
        print(f"✗ {len(failures)} 个岗位语义区分度不足（σ < {args.min_std}）：")
        for job_id, title, n, std in failures:
            print(f"    [{job_id}] {title}  n={n}  σ={std:.1f}")
        print()
        print("含义：同一岗位的候选人语义分几乎相同，排行榜据此排不出强弱。")
        print("处理：先确认 match_chain 的档内定位规则（v2 起）是否生效；")
        print("      如果候选人确实高度同质，这个告警可以接受，但要在 README 里写明。")
        return 1

    print(f"✓ 全部通过（σ >= {args.min_std}，或样本数 < {args.min_samples} 不判定）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
