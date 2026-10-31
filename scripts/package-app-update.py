"""Publish an audited app update and the already frozen external card collection."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEST = Path.home() / 'Desktop/cc产品/映光卡匣-1.0.4'
APK = ROOT / 'android/app/build/outputs/apk/release/app-release.apk'
COLLECTION = Path.home() / 'Desktop/cc产品/映光卡匣-新增11张闪卡-20261016.zip'
EXPECTED_COLLECTION_SHA = '7a80bb9a7bfeb067738c24480d801b57577ffada54cc3db97fe8efb6ee0eeedf'
CERTIFICATE_SHA = '6a8f55de666bdb67c3939f7653084c33482e1b6654e1a71ca535b25e6c34f7bf'
SDK = Path.home() / 'android-sdk/build-tools/36.0.0'
sys.stdout.reconfigure(encoding='utf-8')


def main():
    spec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/package-release.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    require = helper.require
    native = helper.read_json(ROOT / 'android/verification/1.0.4/native-report.json')
    ui = helper.read_json(ROOT / 'android/verification/1.0.4/download-preview-ui-report.json')
    web = helper.read_json(ROOT / 'verification/frontend/1.0.4/downloads-ui-report.json')
    regression = helper.read_json(ROOT / 'verification/frontend/1.0.4/existing-ui-flow-report.json')
    review = helper.read_json(ROOT / 'verification/frontend/1.0.4/review-report.json')
    for label, report in [('native', native), ('Android UI', ui), ('web UI', web), ('regression', regression), ('review', review)]:
        require(report.get('pass') is True, f'{label} report is not passed')
    apk_hash = helper.sha256(APK)
    require(native['releaseSha256'].lower() == apk_hash and ui['releaseSha256'].lower() == apk_hash,
            'Native tests did not use this exact final APK')
    require(native['certificateSha256'].lower() == CERTIFICATE_SHA, 'Native upgrade certificate differs')
    require(helper.sha256(COLLECTION) == EXPECTED_COLLECTION_SHA, 'Frozen external card collection changed')
    cards = helper.load_cards(ROOT)
    apk = helper.validate_apk(APK, ROOT, cards, SDK / 'lib/apksigner.jar')
    helper.validate_runtime(ROOT, cards)
    metadata = subprocess.run([str(SDK / 'aapt.exe'), 'dump', 'badging', str(APK)], capture_output=True,
                              text=True, encoding='utf-8', errors='replace', check=True).stdout
    require("versionCode='5'" in metadata and "versionName='1.0.4'" in metadata, 'Wrong release version')
    require('application-debuggable' not in metadata, 'Release unexpectedly enables app debugging')
    require('android.permission.INTERNET' not in metadata, 'Offline app unexpectedly requests network permission')
    signing = subprocess.run([str(Path(__import__('os').environ['JAVA_HOME']) / 'bin/java.exe'), '-jar',
                              str(SDK / 'lib/apksigner.jar'), 'verify', '--print-certs', str(APK)],
                             capture_output=True, text=True, encoding='utf-8', check=True).stdout
    certificate = re.search(r'certificate SHA-256 digest: ([a-fA-F0-9]+)', signing)
    require(certificate and certificate.group(1).lower() == CERTIFICATE_SHA, 'Release certificate changed')
    require(not DEST.exists(), 'Delivery directory exists; preserve it and inspect before retry')
    DEST.mkdir(parents=True)
    shutil.copy2(APK, DEST / '映光卡匣-1.0.4.apk')
    shutil.copy2(COLLECTION, DEST / '新增11张闪卡合集.zip')
    (DEST / '使用说明.txt').write_text(
        '映光卡匣 1.0.4 · 下载扫描与免导入预览\n\n'
        '安装：直接安装映光卡匣-1.0.4.apk覆盖旧版，不要先卸载，原有卡库与收藏参数会保留。\n\n'
        '快捷导入：将闪卡.ruic或合集ZIP下载到手机/平板Download目录。\n'
        '打开应用→导入卡片，首次允许扫描下载目录。Android11及以上会打开“所有文件访问权限”设置，开启后返回即可自动扫描；应用只扫描下载目录及子文件夹，不上传文件。\n'
        '勾选文件后点导入所选；每批最多50个文件。合集ZIP可直接预览和导入，无需先解压。重复内容会跳过，删卡后可重新导入。\n'
        '不授权或文件在其他位置时，仍可点手动选择文件。备份恢复也继续从手动入口选择原备份ZIP。\n\n'
        '先看再收：下载列表每个文件右侧有预览；打开单卡或合集都不会先写入正式卡库。\n'
        '手机文件管理器中点.ruic或闪卡ZIP，选择映光卡匣打开；也可从文件管理器分享文件给映光卡匣。部分微信/文件管理器不暴露自定义类型的打开方式时，先保存到下载目录，再从App扫描预览。\n'
        '预览默认全屏、参数折叠；合集可前后切卡。点收入卡库才正式保存，返回会清理临时预览。\n\n'
        '新增11张闪卡合集.zip就是本批009—019卡片；内置8张保持，导入后共19张（旧的自行导入卡另计）。\n'
        '已完成签名/资源核验、浏览器流程、Android15模拟器的实际权限、下载扫描、合集预览/导入和原库保留检查。荣耀实体平板仍需实际安装验收。\n', encoding='utf-8-sig')
    report = {'pass': True, 'version': '1.0.4', 'versionCode': 5, 'apk': apk,
              'certificateSha256': CERTIFICATE_SHA, 'externalCollectionSha256': EXPECTED_COLLECTION_SHA,
              'nativeAssertions': native['nativeAssertions'], 'nativeUiChecks': len(ui['checks']),
              'webUiChecks': len(web['checks']), 'existingUiChecks': len(regression['checks']),
              'physicalHonorTested': False, 'wechatSent': False}
    (DEST / '交付核验.json').write_bytes(helper.json_bytes(report))
    manifest = {path.name: helper.sha256(path) for path in sorted(DEST.iterdir()) if path.is_file()}
    (DEST / 'SHA256SUMS.txt').write_text(''.join(f'{digest}  {name}\n' for name, digest in manifest.items()), encoding='utf-8-sig')
    for name, digest in manifest.items():
        require(helper.sha256(DEST / name) == digest, f'Published file differs: {name}')
    evidence = {'pass': True, 'directory': str(DEST), 'files': manifest, 'sendFiles':
                [str(DEST / name) for name in ('映光卡匣-1.0.4.apk', '新增11张闪卡合集.zip', '使用说明.txt')],
                'computerUseStatus': 'JavaScript kernel initialization failed after reset; files not sent; shutdown not requested'}
    (ROOT / 'android/verification/1.0.4/delivery-report.json').write_bytes(helper.json_bytes(evidence))
    print(json.dumps({'directory': str(DEST), 'apkSha256': apk_hash, 'matchedRuntimeFiles': apk['matchedRuntimeFiles']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
