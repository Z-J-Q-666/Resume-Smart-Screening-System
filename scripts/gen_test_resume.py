"""生成一份用于测试的 PDF 简历。

用法：
    conda activate ai
    python scripts/gen_test_resume.py

输出：demo/resumes/林嘉伟_Java后端.pdf
改内容直接改下面的 SECTIONS 即可，字体走系统自带中文字体，无需额外资源。
"""

from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (HRFlowable, Paragraph, SimpleDocTemplate,
                                Spacer)

OUT = Path(__file__).resolve().parent.parent / "demo" / "resumes" / "林嘉伟_Java后端.pdf"


def register_fonts() -> tuple[str, str]:
    """优先宋体（简历正文更像正式文档），失败退回黑体。"""
    try:
        pdfmetrics.registerFont(TTFont("Song", "C:/Windows/Fonts/simsun.ttc", subfontIndex=0))
        body = "Song"
    except Exception:
        pdfmetrics.registerFont(TTFont("Song", "C:/Windows/Fonts/simhei.ttf"))
        body = "Song"
    pdfmetrics.registerFont(TTFont("Hei", "C:/Windows/Fonts/simhei.ttf"))
    return body, "Hei"


BODY_FONT, TITLE_FONT = register_fonts()

NAME = ParagraphStyle("name", fontName=TITLE_FONT, fontSize=22, leading=28, spaceAfter=2)
SUB = ParagraphStyle("sub", fontName=BODY_FONT, fontSize=9.5, leading=16,
                     textColor=colors.HexColor("#4a4f57"))
H2 = ParagraphStyle("h2", fontName=TITLE_FONT, fontSize=12.5, leading=18,
                    spaceBefore=10, spaceAfter=4, textColor=colors.HexColor("#1f2d3d"))
TXT = ParagraphStyle("txt", fontName=BODY_FONT, fontSize=10, leading=17.5,
                     alignment=TA_LEFT, wordWrap="CJK")
ROLE = ParagraphStyle("role", fontName=TITLE_FONT, fontSize=10.5, leading=17,
                      spaceBefore=6, spaceAfter=1)
META = ParagraphStyle("meta", fontName=BODY_FONT, fontSize=9, leading=14,
                      textColor=colors.HexColor("#6b7280"), spaceAfter=3)
BULLET = ParagraphStyle("bullet", parent=TXT, leftIndent=10, firstLineIndent=-10)

SECTIONS = [
    ("教育背景", [
        ("role", "中南大学　计算机科学与技术　本科"),
        ("meta", "2015.09 - 2019.06　长沙　|　主修：数据结构、操作系统、数据库系统、计算机网络"),
    ]),
    ("工作经历", [
        ("role", "深圳市云图数据科技有限公司　资深 Java 开发工程师"),
        ("meta", "2022.04 - 至今　深圳"),
        ("bullet", "· 负责订单履约中台的后端设计与迭代，基于 Spring Boot + MyBatis-Plus 提供 80 余个接口，"
                   "支撑日均 60 万单、峰值 3200 QPS。"),
        ("bullet", "· 主导履约状态机重构：把原先散落在 4 个服务里的状态流转收敛到一张状态表，"
                   "用 RocketMQ 事务消息保证「扣库存—生单—发通知」最终一致，线上超时单量下降 92%。"),
        ("bullet", "· 用 Redis 重建热点 SKU 缓存与分布式锁，缓存命中率 96%，锁误删问题通过 Lua 比对 token 解决。"),
        ("bullet", "· MySQL 单表 1.8 亿行时按商户号分库分表（ShardingSphere），配合覆盖索引把慢查询从日均 400 条压到个位数。"),

        ("role", "广州智联软件有限公司　Java 开发工程师"),
        ("meta", "2019.07 - 2022.03　广州"),
        ("bullet", "· 参与企业协同办公系统开发，负责审批流与权限模块，Spring Boot + MyBatis + MySQL。"),
        ("bullet", "· 把审批节点的同步推送改为 RocketMQ 异步解耦，提交接口 P99 从 1800ms 降到 210ms。"),
        ("bullet", "· 搭建 Jenkins + Docker 的自动化发布流程，单次发版从 40 分钟缩短到 6 分钟。"),
    ]),
    ("专业技能", [
        ("bullet", "· 编程语言：Java（熟练）、SQL、Python（能写脚本）"),
        ("bullet", "· 框架与中间件：Spring Boot、Spring Cloud、MyBatis、RocketMQ、Kafka、Redis、Elasticsearch"),
        ("bullet", "· 数据库与工具：MySQL、PostgreSQL、Linux、Docker、Git、Maven、Jenkins"),
        ("bullet", "· 工程能力：JVM 调优、分库分表、缓存治理、消息队列削峰与最终一致性、接口性能优化"),
    ]),
    ("自我评价", [
        ("txt", "七年后端开发经验，长期在交易与履约链路工作，习惯先用数据定位瓶颈再动手改架构；"
                "对消息队列削峰、缓存一致性、分库分表有完整的落地经验，能独立负责一个中台的从 0 到 1。"),
    ]),
]


def build():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(OUT), pagesize=A4,
        leftMargin=20 * mm, rightMargin=20 * mm, topMargin=16 * mm, bottomMargin=16 * mm,
        title="林嘉伟 - Java 后端开发工程师", author="林嘉伟",
    )
    story = [
        Paragraph("林嘉伟", NAME),
        Paragraph("求职意向：Java 后端开发工程师（深圳）　|　7 年经验　|　本科", SUB),
        Paragraph("电话 186-1234-5678　·　邮箱 linjiawei.dev@example.com　·　"
                  "现居 深圳市南山区", SUB),
        Spacer(1, 6),
        HRFlowable(width="100%", thickness=1.1, color=colors.HexColor("#1f2d3d"), spaceAfter=2),
    ]

    for title, blocks in SECTIONS:
        story.append(Paragraph(title, H2))
        story.append(HRFlowable(width="100%", thickness=0.6,
                                color=colors.HexColor("#c9cfda"), spaceAfter=3))
        for kind, text in blocks:
            style = {"role": ROLE, "meta": META, "bullet": BULLET, "txt": TXT}[kind]
            story.append(Paragraph(text, style))

    doc.build(story)
    print("generated:", OUT)
    print("size:", OUT.stat().st_size, "bytes")


if __name__ == "__main__":
    build()
