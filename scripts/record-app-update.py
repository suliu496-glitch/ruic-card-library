"""Record the validated app update and preserve the project's dated history."""
from pathlib import Path
import json
import re
import shutil
import sys

sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parents[1]
VAULT = Path('E:/obsidian-vault')
PROJECT = VAULT / '05-项目/映光卡匣'
HEADING = '2026-10-16 1.0.4 下载扫描与免导入预览'


def main():
    qa = ROOT / 'android/verification/1.0.4'
    delivery = json.loads((qa / 'delivery-report.json').read_text(encoding='utf-8-sig'))
    native = json.loads((qa / 'native-report.json').read_text(encoding='utf-8-sig'))
    ui = json.loads((qa / 'download-preview-ui-report.json').read_text(encoding='utf-8-sig'))
    assert delivery['pass'] and native['pass'] and ui['pass']
    assert f'## {HEADING}' not in (PROJECT / '进度.md').read_text(encoding='utf-8-sig')
    assert len([path for path in (VAULT / '05-项目').iterdir() if path.is_dir()]) == 13
    apk_sha = native['releaseSha256']
    destination = delivery['directory']
    status = '1.0.4 已保存本机，微信发送待工具恢复；荣耀真机待验'
    current = ('当前结果（2026-10-16）：APK1.0.4/code5已保存本机，新增下载目录扫描、合集ZIP直接导入和免入库临时预览，'
               '文件管理器可打开/分享闪卡。内置8张与外部11包保留，全部导入共19张（其他自行导入卡另计）。'
               '96项新增网页、117项旧流程、139项原生断言及46项Android15实际界面检查通过，同签名升级保留旧卡与参数。'
               '荣耀真机待验；本轮微信发送因computer-use内核启动失败尚未执行，未关机。')
    note = (f'\n\n## {HEADING}\n\n'
            '用户要求不再手动寻找下载文件，并希望闪卡先预览、再决定是否入库。1.0.4保持云白白蓝界面，'
            '点导入自动扫描公共Download及子目录；勾选批量导入，最多50个文件一批，列表最多100候选。'
            '单卡.ruic和合集ZIP均可直接预览/导入，无需解压；普通ZIP、备份与无效路径不进入快捷导入。'
            '备份恢复和其他目录图片仍可手动选择。\n\n'
            '首次扫描在Android11+开启系统“所有文件访问权限”，返回自动扫描；Android8–10使用读取存储权限及Android10兼容标记。'
            '应用只扫描下载目录，不申请网络权限。已明确授予content文档读取权的打开/分享可直接预览，无需先授予扫描权限。\n\n'
            '临时预览放在隔离私有cache，合集可前后切换，拖动和微调不写正式卡库；点收入卡库才保存，返回清理临时会话。'
            '文件管理器VIEW/SEND冷、热启动均接续固定查看器；发送端不暴露自定义类型时，先存Download再扫描。'
            '下载/预览保存共享SHA去重，删卡后可重导；记账失败只撤回本次新增ID，不删除已有卡。\n\n'
            f'成品：`{destination}`，包含 `映光卡匣-1.0.4.apk`（54,590,988字节）、`新增11张闪卡合集.zip`、说明及校验。'
            f'正式APK SHA256：`{apk_sha}`；versionCode5，原证书身份，同签名覆盖安装，勿先卸载。'
            '原1.0.3及009–019独立卡包交付目录保留，未重制美术。\n\n'
            '| 验证 | 最终范围 |\n|------|----------|\n'
            '| 网页 | 96项新增流程、117项旧流程通过，动态token、100候选分批、第51项防护、XSS、临时参数隔离及320/768/1600/1920布局 |\n'
            '| 原生 | 139项实际Android断言通过，真实ZIP预算、源复核、SHA去重、删除重导、AtomicFile故障回滚与重试、隔离预览 |\n'
            '| Android界面 | 46项真实检查通过：首次系统设置返回、实际80MB已发布合集11卡预览/GPU/触摸/五层/横竖屏、保存11、重复跳过12、删除重导1；真实Files应用转授content URI且未授扫描权限也能预览，冷启动文件预览 |\n'
            '| 升级与恢复 | 原1.0.3初升级51个持久文件哈希一致；最终候选再升级49个持久文件一致；旧卡四资源全程不变，UI QA恢复原ID、完整state/localStorage，清测试新增卡/账本/预览cache |\n'
            '| 构建与复审 | Release+lint成功，0错误/6警告；三项P2修复并独立复审关闭，APK签名、版本、无网络/无debug标记及已打包资源SHA核对 |\n\n'
            '边界：实际Android测试为API35/SwiftShader专用模拟器，实体荣耀平板未接入；Android8–10权限分支未做同等系统实测。'
            '真实文件关联转授由系统Files应用发起，ADB root不能伪装合法URI授权。完整历史SAF备份套件未在本轮重跑。\n\n'
            '本轮发送要求：使用computer-use，仅发给微信“文件传输助手”，核对精确会话和全部传输完成后才关机。'
            '当前工具在初始化JavaScript内核时两次报“failed to write kernel assets / 系统找不到指定的路径”，重置无效；'
            '没有执行任何微信UI动作或发送，电脑保持开机。恢复工具后按 `delivery-report.json.sendFiles` 三项继续，不能把保存本机当成微信发送完成。\n\n'
            f'证据：源码 `{ROOT}`；`verification/frontend/1.0.4/` 与 `android/verification/1.0.4/`。'
            '私人完整state和测试APK位于被Git/源码ZIP排除的native-qa目录；公开报告不附签名凭证。\n')
    backup = qa / 'native-qa-kb-before'
    changed = []
    def save(path, text):
        original = path.read_text(encoding='utf-8-sig')
        relative = path.relative_to(VAULT)
        target = backup / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists(): shutil.copy2(path, target)
        text = re.sub(r'(?m)^updated: .*$', 'updated: 2026-10-16', text, count=1)
        path.write_text(text, encoding='utf-8')
        changed.append(str(relative))
    progress = (PROJECT / '进度.md').read_text(encoding='utf-8-sig')
    progress = re.sub(r'(?m)^status: .*$', f'status: {status}', progress, count=1)
    progress = re.sub(r'(?m)^> 当前结果.*$', '> ' + current, progress, count=1)
    save(PROJECT / '进度.md', progress.rstrip() + note)
    overview = (PROJECT / 'README.md').read_text(encoding='utf-8-sig')
    overview = re.sub(r'(?m)^status: .*$', f'status: {status}', overview, count=1)
    start, end = overview.index('将本机图片'), overview.index('\n## 立项三问')
    overview = overview[:start] + '本机图片经清理美化制成真实分层全息收藏卡，配套用户指定的荣耀13.3寸平板离线应用。' + current + '\n' + overview[end:]
    lines = overview.splitlines()
    for i, line in enumerate(lines):
        if line.startswith('| 应用交付目录 |'): lines[i] = f'| 应用交付目录 | `{destination}`；旧1.0.3目录保留 |'
        if line.startswith('| 正式安装包 |'): lines[i] = '| 正式安装包 | 映光卡匣-1.0.4.apk，54,590,988字节，versionCode5，原签名证书身份（v2） |'
        if line.startswith('| 配套文件 |'): lines[i] = line.replace('| 配套文件 |', '| 历史1.0.3完整配套 |', 1)
    overview = '\n'.join(lines) + '\n'
    overview = overview.replace('## 2026-10-16 扩卡验证', '## 2026-10-16 扩卡阶段验证（应用1.0.3）', 1)
    overview = overview.replace('当前结果（2026-10-16）：应用仍为1.0.3', '扩卡交付时结果：应用为1.0.3')
    overview = overview.replace('1.0.3 当前 APK', '1.0.3 历史 APK')
    overview += f'\n## 1.0.4 入口\n\n详见 [[05-项目/映光卡匣/进度#{HEADING}|下载扫描与免导入预览]]。本机成品已验，微信发送仍待computer-use恢复；确认发送完成前不关机。\n'
    save(PROJECT / 'README.md', overview)
    summary = (PROJECT / '项目总结.md').read_text(encoding='utf-8-sig')
    summary = re.sub(r'(?m)^status: .*$', f'status: {status}', summary, count=1)
    summary = re.sub(r'(?m)^\| 最新成品 \|.*$', lambda _: f'| 最新成品 | `{destination}`；历史1.0.3完整配套目录保留 |', summary, count=1)
    summary += f'\n\n## 1.0.4 扩展\n\n{current}\n\n下载入口由手动找文件改为自动扫描，合集直接读内部ruic；预览与正式卡库隔离，明确保存才提交。权限、ZIP实际预算和记账事务都需真实Android验证。详细交付、测试边界及微信待发送条件见 [[05-项目/映光卡匣/进度#{HEADING}|1.0.4进度]]。\n'
    save(PROJECT / '项目总结.md', summary)
    pitfalls = (PROJECT / '踩坑记录.md').read_text(encoding='utf-8-sig')
    pitfalls += ('\n\n## 2026-10-16 下载扫描与预览的接缝\n\n'
                 'Android11+非媒体.ruic/ZIP直读Download需要特殊文件权限，SAF目录授权不能选择Download根目录；授权返回需重查权限，不信空resultCode。'
                 '真正content URI转授应由持有该文档权利的Files发送端发起，root/shell测试不能凭空造grant。\n\n'
                 '原生只接受50文件而前端候选可到100，默认/全选/手选/提交四处必须同限；mock须模拟源变动与opaque token失效。'
                 '正式commit后若账本写失败，不能报失败却留下新卡；先做账本提议副本，写/同步/读回成功才换内存，失败仅撤回本次addedIds。'
                 '预览保存还要记原Download来源fingerprint，否则回扫误报未导入；合集旧activeIds与本次new IDs分开，最终标记失败不可删除旧卡。\n\n'
                 'Android/data路径别名会使测试File绝对路径与canonical不等。测试应走生产scan→resolveCurrentFile→preview接口，不能为过测试放宽symlink guard。'
                 '实际预览层数按源包检查，首卡四层、山/江/琴五层，不能一概要求五层。\n')
    save(PROJECT / '踩坑记录.md', pitfalls)
    for relative, needle, row in [
        ('05-项目/项目-MOC.md', '| [[映光卡匣/README', '| [[映光卡匣/README\\|映光卡匣]] | 🟡 1.0.4本机交付，微信发送待工具恢复，荣耀真机待验 | 2026-10 | 下载扫描/合集直接导入、临时预览与文件管理器打开；96新网页/117旧流程、139原生/46安卓UI通过，旧卡与参数保持。内置8+外部11；见 [[映光卡匣/进度\\|进度]]、[[映光卡匣/项目总结\\|总结]]、[[映光卡匣/踩坑记录\\|踩坑]] |'),
        ('个人信息.md', '| [[05-项目/映光卡匣/README', '| [[05-项目/映光卡匣/README\\|映光卡匣]] | 🟡 APK1.0.4已保存本机，微信发送待工具恢复，荣耀真机待验 | 自动扫描下载闪卡、合集免解压、免入库预览与文件关联；96/117网页、139原生/46安卓UI通过，同签名升级保数据。内置8+外部11。见 [[05-项目/映光卡匣/进度\\|进度]] | |')]:
        path = VAULT / relative
        lines = path.read_text(encoding='utf-8-sig').splitlines()
        hits = [i for i, line in enumerate(lines) if line.startswith(needle)]
        assert len(hits) == 1
        lines[hits[0]] = row
        save(path, '\n'.join(lines) + '\n')
    entry = (VAULT / 'README.md').read_text(encoding='utf-8-sig')
    header = '| 日期 | 变更 |\n|------|------|\n'
    assert header in entry
    entry = entry.replace(header, header + f'| 2026-10-16 | [[05-项目/映光卡匣/进度#{HEADING}\\|映光卡匣1.0.4]]本机交付：下载扫描、合集直入、免入库预览和文件关联；96/117网页、139原生/46安卓UI、同签名保数据通过。内置8+外部11保持，项目仍13个；微信文件传输助手发送因computer-use初始化错误待续，未关机，荣耀真机待验 |\n', 1)
    save(VAULT / 'README.md', entry)
    result = {'pass': True, 'files': changed, 'projectCount': 13, 'wechatSent': False, 'shutdownRequested': False}
    (qa / 'knowledge-sync-report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
