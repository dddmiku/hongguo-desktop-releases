"""Publish an immutable TV release with a complete inherited PC migration feed."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import quote
from github import request, token

ROOT=Path(__file__).resolve().parents[1]
REPO="dddmiku/hongguo-desktop-releases"
API="https://api.github.com/repos/"+REPO
SIGNING_CERT="7009c6ca8eedca64e259e16964e4112f6b181aaf7e47d9680a8d05b33af364e8"

def version():
    source=(ROOT/"app/build.gradle").read_text(encoding="utf-8")
    return re.search(r'versionName\s+"([0-9.]+)"',source).group(1),int(re.search(r'versionCode\s+(\d+)',source).group(1))

def feed(previous, tv_version, code, apk, notes):
    result=copy.deepcopy(previous)
    windows=result.get("platforms",{}).get("windows-x86_64")
    if not windows or not windows.get("url") or not result.get("version"):
        raise RuntimeError("现有 PC 更新信息缺失，已停止发布以保留更新入口")
    result["platforms"]["android-tv"]={"version":tv_version,"versionCode":code,"url":"https://github.com/"+REPO+"/releases/download/tv-v"+tv_version+"/"+apk.name,"size":apk.stat().st_size,"sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),"notes":notes}
    return result

def call(method,path,secret,**kwargs):
    status,data=request(method,API+path,secret,**kwargs)
    if status not in (200,201,204):raise RuntimeError("GitHub 操作失败 HTTP "+str(status))
    return data

def require_draft(release):
    if not release.get("draft"):raise RuntimeError("该版本已公开，拒绝替换已发布资产；应递增版本号")

def verify_signature(apk):
    sdk=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        properties=ROOT/"local.properties"
        if properties.is_file():
            match=re.search(r"^sdk.dir=(.+)$",properties.read_text(encoding="utf-8"),re.M)
            if match:sdk=match.group(1).strip().replace("\\\\","\\").replace("\\:",":")
    tools=sorted((Path(sdk)/"build-tools").glob("*/apksigner.bat")) if sdk else []
    if not tools:raise RuntimeError("找不到 apksigner，无法确认安装包能覆盖原 TV 版")
    checked=subprocess.run([str(tools[-1]),"verify","--print-certs",str(apk)],capture_output=True,text=True)
    if checked.returncode!=0 or SIGNING_CERT not in checked.stdout.lower():raise RuntimeError("正式安装包签名不匹配原 TV 版，已停止发布")

def upload(release,path,secret):
    require_draft(call("GET","/releases/"+str(release["id"]),secret))
    digest="sha256:"+hashlib.sha256(path.read_bytes()).hexdigest()
    current=call("GET","/releases/"+str(release["id"])+"/assets",secret)
    old=next((asset for asset in current if asset["name"]==path.name),None)
    if old:
        if old.get("digest")==digest and old["size"]==path.stat().st_size:return
        require_draft(call("GET","/releases/"+str(release["id"]),secret))
        call("DELETE","/releases/assets/"+str(old["id"]),secret)
    url=release["upload_url"].split("{")[0]+"?name="+quote(path.name)
    status,asset=request("POST",url,secret,raw=path.read_bytes(),content_type="application/octet-stream")
    if status!=201 or asset.get("size")!=path.stat().st_size or asset.get("digest") not in (None,digest):raise RuntimeError("上传资产校验失败")
    print("已上传：",path.name,asset["size"],"bytes",flush=True)

def publish(source_commit):
    tv_version,code=version();tag="tv-v"+tv_version;secret=token()
    apk=ROOT/"app/build/outputs/apk/release"/("hongguo-tv-"+tv_version+".apk")
    if not apk.is_file():raise RuntimeError("正式签名安装包尚未生成")
    verify_signature(apk)
    notes=(ROOT/"docs/release-notes.md").read_text(encoding="utf-8")
    previous_release=call("GET","/releases/latest",secret)
    asset=next((a for a in previous_release["assets"] if a["name"]=="latest.json"),None)
    if not asset:raise RuntimeError("原有更新清单暂时无法读取")
    status,previous=request("GET",asset["browser_download_url"])
    if status!=200:raise RuntimeError("读取原更新清单失败")
    if isinstance(previous,bytes):previous=json.loads(previous)
    staging=ROOT/"_work/release";staging.mkdir(parents=True,exist_ok=True)
    public_apk=staging/("hongguo-tv-"+tv_version+".APK");shutil.copyfile(apk,public_apk)
    latest=staging/"latest.json";latest.write_text(json.dumps(feed(previous,tv_version,code,public_apk,notes),ensure_ascii=False,indent=2),encoding="utf-8")
    sums=staging/"SHA256SUMS.txt";sums.write_text(hashlib.sha256(public_apk.read_bytes()).hexdigest()+"  "+public_apk.name+"\n",encoding="utf-8")
    remote=call("GET","/commits/"+source_commit,secret)
    if remote.get("sha")!=source_commit:raise RuntimeError("正式源码提交尚未推送")
    status,release=request("GET",API+"/releases/tags/"+tag,secret)
    if status==404:release=call("POST","/releases",secret,data={"tag_name":tag,"target_commitish":source_commit,"name":"红果客厅 TV "+tv_version,"body":notes,"draft":True,"prerelease":False})
    elif status!=200:raise RuntimeError("查询发布版本失败")
    require_draft(release)
    ref_status,ref=request("GET",API+"/git/ref/tags/"+tag,secret)
    if ref_status==200 and ref.get("object",{}).get("sha")!=source_commit:raise RuntimeError("该草稿已有不同源码标签，请检查后再发布")
    for path in (public_apk,latest,sums):upload(release,path,secret)
    # Re-read before publication: a concurrently released PC version must never be lost.
    current=call("GET","/releases/latest",secret)
    if current["id"]!=previous_release["id"]:raise RuntimeError("发布期间 PC 更新入口已变化，请重新生成 TV 清单")
    require_draft(call("GET","/releases/"+str(release["id"]),secret))
    done=call("PATCH","/releases/"+str(release["id"]),secret,data={"draft":False,"make_latest":"true","target_commitish":source_commit,"body":notes,"name":"红果客厅 TV "+tv_version})
    if done.get("draft"):raise RuntimeError("发布未完成")
    print("已发布：",done["html_url"],flush=True)

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--source-commit",required=True);args=parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{40}",args.source_commit):raise SystemExit("源码提交格式不正确")
    try:publish(args.source_commit)
    except Exception as e:print("发布停止：",str(e),file=sys.stderr);raise SystemExit(1)
