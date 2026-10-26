"""生成演示用简历 PDF（文本型 + 一份扫描件）。

用法：
    conda activate ai
    python scripts/gen_demo_resumes.py
输出：demo/resumes/*.pdf
"""

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer

FONT_PATH = "C:/Windows/Fonts/simhei.ttf"
pdfmetrics.registerFont(TTFont("SimHei", FONT_PATH))

OUT_DIR = Path(__file__).resolve().parent.parent / "demo" / "resumes"
OUT_DIR.mkdir(parents=True, exist_ok=True)

TITLE = ParagraphStyle("title", fontName="SimHei", fontSize=18, leading=24, spaceAfter=4)
H2 = ParagraphStyle("h2", fontName="SimHei", fontSize=12, leading=18, spaceBefore=8, spaceAfter=4)
BODY = ParagraphStyle("body", fontName="SimHei", fontSize=10, leading=16)


def build(name, lines):
    doc = SimpleDocTemplate(
        str(OUT_DIR / f"{name}.pdf"), pagesize=A4,
        leftMargin=20 * mm, rightMargin=20 * mm, topMargin=18 * mm, bottomMargin=18 * mm,
        title=name,
    )
    story = []
    for kind, text in lines:
        if kind == "h1":
            story.append(Paragraph(text, TITLE))
        elif kind == "h2":
            story.append(Paragraph(text, H2))
        elif kind == "gap":
            story.append(Spacer(1, 4 * mm))
        else:
            story.append(Paragraph(text, BODY))
    doc.build(story)
    print("generated", OUT_DIR / f"{name}.pdf")


RESUMES = {
    # ① 完全匹配：Java / Spring Boot / MySQL / RocketMQ，本科，5 年以上
    "张伟_Java后端_5年": [
        ("h1", "张伟"),
        ("body", "电话：13800138000&nbsp;&nbsp;&nbsp;邮箱：zhangwei@example.com&nbsp;&nbsp;&nbsp;现居：深圳"),
        ("h2", "教育背景"),
        ("body", "2016.09 - 2020.06&nbsp;&nbsp;华南理工大学&nbsp;&nbsp;计算机科学与技术&nbsp;&nbsp;本科"),
        ("h2", "工作经历"),
        ("body", "<b>2023.07 - 至今&nbsp;&nbsp;广州云链网络科技有限公司&nbsp;&nbsp;高级 Java 开发工程师</b>"),
        ("body", "主导支付网关重构，引入 RocketMQ 做异步削峰与最终一致性对账，"
                 "峰值 QPS 从 800 提升到 5000；使用 Redis 做热点账户缓存，接口 P99 从 420ms 降到 90ms。"),
        ("body", "<b>2020.07 - 2023.06&nbsp;&nbsp;深圳启明科技有限公司&nbsp;&nbsp;Java 后端开发工程师</b>"),
        ("body", "负责电商后台订单与库存系统开发，基于 Spring Boot + MyBatis 提供 60 余个接口，"
                 "MySQL 分库分表支撑日均 30 万单；用 RocketMQ 解耦订单与库存扣减。"),
        ("h2", "专业技能"),
        ("body", "编程语言：Java（熟练）、SQL、Python（了解）"),
        ("body", "框架与中间件：SpringBoot、Spring Cloud、MyBatis、RocketMQ、Redis、Kafka"),
        ("body", "数据库与工具：MySQL、Linux、Git、Maven、Docker、JVM 调优"),
        ("h2", "自我评价"),
        ("body", "五年后端经验，长期在高并发交易链路工作，熟悉消息队列削峰与缓存治理，能独立负责系统重构。"),
    ],

    # ② 技能匹配但年限差 2 年：Java 技术栈齐全，只有 1 年经验
    "李婷_Java后端_1年": [
        ("h1", "李婷"),
        ("body", "电话：13900139000&nbsp;&nbsp;&nbsp;邮箱：liting@example.com&nbsp;&nbsp;&nbsp;现居：广州"),
        ("h2", "教育背景"),
        ("body", "2019.09 - 2023.06&nbsp;&nbsp;广东工业大学&nbsp;&nbsp;软件工程&nbsp;&nbsp;本科"),
        ("h2", "工作经历"),
        ("body", "<b>2025.07 - 至今&nbsp;&nbsp;佛山智联软件有限公司&nbsp;&nbsp;Java 开发工程师</b>"),
        ("body", "参与企业内部审批系统开发，使用 Spring Boot + MyBatis 编写审批流与权限模块接口，"
                 "MySQL 做数据存储，Redis 缓存组织架构；参与一次 RocketMQ 消息通知接入。"),
        ("h2", "实习经历"),
        ("body", "2024.09 - 2025.06&nbsp;&nbsp;广州某创业公司&nbsp;&nbsp;后端实习生：维护后台管理系统的增删改查接口。"),
        ("h2", "专业技能"),
        ("body", "Java、SpringBoot、MyBatis、MySQL、Redis、RocketMQ、Git、Linux"),
        ("h2", "自我评价"),
        ("body", "基础扎实，代码规范，希望在消息队列与高并发方向继续深入。"),
    ],

    # ③ 完全不匹配：前端
    "王浩_前端_3年": [
        ("h1", "王浩"),
        ("body", "电话：13700137000&nbsp;&nbsp;&nbsp;邮箱：wanghao@example.com&nbsp;&nbsp;&nbsp;现居：杭州"),
        ("h2", "教育背景"),
        ("body", "2018.09 - 2021.06&nbsp;&nbsp;浙江经济职业技术学院&nbsp;&nbsp;计算机应用技术&nbsp;&nbsp;大专"),
        ("h2", "工作经历"),
        ("body", "<b>2023.03 - 至今&nbsp;&nbsp;杭州某电商代运营公司&nbsp;&nbsp;前端开发工程师</b>"),
        ("body", "负责商家后台前端开发，Vue3 + Element Plus + Pinia 搭建 20 余个页面，"
                 "封装通用表格与表单组件；使用 ECharts 完成经营看板可视化。"),
        ("body", "<b>2021.07 - 2023.02&nbsp;&nbsp;杭州某数字营销公司&nbsp;&nbsp;前端开发</b>"),
        ("body", "活动落地页与微信小程序开发，HTML/CSS/JavaScript 还原设计稿，配合后端联调接口。"),
        ("h2", "专业技能"),
        ("body", "Vue、Element Plus、Pinia、Vite、JavaScript、TypeScript、HTML、CSS、ECharts、微信小程序"),
        ("h2", "自我评价"),
        ("body", "三年前端经验，注重交互细节与还原度。"),
    ],

    # ④ 强匹配：硕士 + 4 年 + 技术栈齐全
    "陈晨_Java后端_4年硕士": [
        ("h1", "陈晨"),
        ("body", "电话：13600136000&nbsp;&nbsp;&nbsp;邮箱：chenchen@example.com&nbsp;&nbsp;&nbsp;现居：深圳"),
        ("h2", "教育背景"),
        ("body", "2018.09 - 2021.06&nbsp;&nbsp;中山大学&nbsp;&nbsp;计算机技术&nbsp;&nbsp;硕士"),
        ("body", "2014.09 - 2018.06&nbsp;&nbsp;华南师范大学&nbsp;&nbsp;软件工程&nbsp;&nbsp;本科"),
        ("h2", "工作经历"),
        ("body", "<b>2022.04 - 至今&nbsp;&nbsp;深圳某金融科技公司&nbsp;&nbsp;Java 开发工程师</b>"),
        ("body", "负责信贷核心系统的额度与放款模块，Spring Boot + MyBatis-Plus 实现业务接口，"
                 "RocketMQ 处理放款结果异步通知，Redis 做幂等与限流，MySQL 处理千万级流水。"),
        ("body", "<b>2021.07 - 2022.03&nbsp;&nbsp;广州某 SaaS 公司&nbsp;&nbsp;后端开发</b>"),
        ("body", "租户管理模块开发，Spring Boot + MySQL + Redis，参与权限体系重构。"),
        ("h2", "专业技能"),
        ("body", "Java、Spring Boot、Spring Cloud、MyBatis、MySQL、Redis、RocketMQ、Linux、Docker、Git"),
        ("h2", "自我评价"),
        ("body", "硕士毕业后专注金融后端，熟悉资金类系统的幂等与对账设计。"),
    ],

    # ⑤ 部分匹配：数据/算法方向
    "刘洋_算法_3年硕士": [
        ("h1", "刘洋"),
        ("body", "电话：13500135000&nbsp;&nbsp;&nbsp;邮箱：liuyang@example.com&nbsp;&nbsp;&nbsp;现居：北京"),
        ("h2", "教育背景"),
        ("body", "2018.09 - 2021.06&nbsp;&nbsp;北京邮电大学&nbsp;&nbsp;人工智能&nbsp;&nbsp;硕士"),
        ("h2", "工作经历"),
        ("body", "<b>2021.07 - 至今&nbsp;&nbsp;北京某推荐算法公司&nbsp;&nbsp;算法工程师</b>"),
        ("body", "负责推荐召回与排序模型迭代，PyTorch 训练双塔模型，Python 编写离线特征管道，"
                 "使用 Spark 处理日增 2TB 样本；线上服务用 Flask 封装，QPS 300。"),
        ("h2", "专业技能"),
        ("body", "Python、PyTorch、Pandas、NumPy、Spark、Flask、SQL、Linux、Docker"),
        ("h2", "自我评价"),
        ("body", "熟悉推荐系统全链路，工程能力偏 Python 侧。"),
    ],

    # ⑥ 不匹配：测试
    "赵敏_测试_6年": [
        ("h1", "赵敏"),
        ("body", "电话：13400134000&nbsp;&nbsp;&nbsp;邮箱：zhaomin@example.com&nbsp;&nbsp;&nbsp;现居：上海"),
        ("h2", "教育背景"),
        ("body", "2014.09 - 2018.06&nbsp;&nbsp;上海应用技术大学&nbsp;&nbsp;信息管理与信息系统&nbsp;&nbsp;本科"),
        ("h2", "工作经历"),
        ("body", "<b>2020.03 - 至今&nbsp;&nbsp;上海某银行软件中心&nbsp;&nbsp;测试工程师</b>"),
        ("body", "负责手机银行 App 的功能与接口测试，编写 300+ 测试用例，使用 Postman 做接口自动化，"
                 "Jenkins 集成回归任务；参与性能测试，用 JMeter 压测转账接口。"),
        ("body", "<b>2018.07 - 2020.02&nbsp;&nbsp;上海某外包公司&nbsp;&nbsp;测试工程师</b>"),
        ("body", "Web 系统功能测试与缺陷跟踪，使用 Jira 管理缺陷。"),
        ("h2", "专业技能"),
        ("body", "功能测试、接口测试、Postman、JMeter、Jenkins、Jira、SQL、Python（脚本）、Linux"),
        ("h2", "自我评价"),
        ("body", "六年金融系统测试经验，熟悉接口自动化与回归流程。"),
    ],

    # ⑦ 匹配：全栈，7 年
    "孙磊_全栈_7年": [
        ("h1", "孙磊"),
        ("body", "电话：13300133000&nbsp;&nbsp;&nbsp;邮箱：sunlei@example.com&nbsp;&nbsp;&nbsp;现居：成都"),
        ("h2", "教育背景"),
        ("body", "2013.09 - 2017.06&nbsp;&nbsp;电子科技大学&nbsp;&nbsp;软件工程&nbsp;&nbsp;本科"),
        ("h2", "工作经历"),
        ("body", "<b>2021.05 - 至今&nbsp;&nbsp;成都某物流科技公司&nbsp;&nbsp;技术负责人</b>"),
        ("body", "带 5 人小组负责运单中台，Spring Boot + MyBatis 提供运单与轨迹接口，"
                 "RocketMQ 承接轨迹上报削峰（日均 2000 万条），Redis 缓存网点信息，"
                 "Vue3 自研运营后台；主导 MySQL 慢查询治理，P95 从 900ms 降到 120ms。"),
        ("body", "<b>2017.07 - 2021.04&nbsp;&nbsp;成都某软件公司&nbsp;&nbsp;Java 开发工程师</b>"),
        ("body", "ERP 模块开发与实施，Spring MVC + MyBatis + MySQL，负责库存与采购模块。"),
        ("h2", "专业技能"),
        ("body", "Java、Spring Boot、Spring Cloud、MyBatis、MySQL、Redis、RocketMQ、Vue、"
                 "JavaScript、Linux、Docker、Git、Maven"),
        ("h2", "自我评价"),
        ("body", "七年后端为主、能做前端，熟悉物流行业高并发写入场景。"),
    ],

    # ⑧ 应届/实习：年限接近 0
    "周雨_应届_实习": [
        ("h1", "周雨"),
        ("body", "电话：13200132000&nbsp;&nbsp;&nbsp;邮箱：zhouyu@example.com&nbsp;&nbsp;&nbsp;现居：武汉"),
        ("h2", "教育背景"),
        ("body", "2022.09 - 2026.06&nbsp;&nbsp;华中科技大学&nbsp;&nbsp;计算机科学与技术&nbsp;&nbsp;本科"),
        ("h2", "实习经历"),
        ("body", "2025.07 - 2025.12&nbsp;&nbsp;武汉某互联网公司&nbsp;&nbsp;Java 开发实习生："
                 "参与课程管理模块开发，Spring Boot + MyBatis + MySQL，编写 20 余个接口。"),
        ("h2", "校内项目"),
        ("body", "校园二手交易平台（毕业设计）：Spring Boot + Vue3 + MySQL + Redis，实现商品发布与订单流程。"),
        ("h2", "专业技能"),
        ("body", "Java、SpringBoot、MyBatis、MySQL、Redis、Vue、Git、Linux"),
        ("h2", "自我评价"),
        ("body", "2026 届本科毕业生，基础扎实，实习期间独立完成模块开发。"),
    ],
}


def build_scanned_like():
    """生成一份只有图形、没有文本层的 PDF，用来验证 2001 分支。"""
    from reportlab.pdfgen import canvas

    path = OUT_DIR / "扫描件_无文本层.pdf"
    c = canvas.Canvas(str(path), pagesize=A4)
    c.setFillGray(0.92)
    c.rect(40, 40, A4[0] - 80, A4[1] - 80, stroke=0, fill=1)
    c.setFillGray(0.55)
    for i in range(18):
        y = A4[1] - 110 - i * 34
        c.rect(70, y, A4[0] - 200 - (i % 4) * 40, 9, stroke=0, fill=1)
    c.showPage()
    c.save()
    print("generated", path, "(no text layer)")


if __name__ == "__main__":
    for name, lines in RESUMES.items():
        build(name, lines)
    build_scanned_like()
