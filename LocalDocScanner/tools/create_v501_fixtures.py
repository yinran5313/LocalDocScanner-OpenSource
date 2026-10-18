from pathlib import Path
from reportlab.pdfgen import canvas
from PIL import Image, ImageDraw, ImageFont
out=Path(__file__).resolve().parents[2]/'交付'/'V5.0.1'/'测试输入';out.mkdir(parents=True,exist_ok=True)
c=canvas.Canvas(str(out/'ShiYeAudit501.pdf'),pagesize=(595,842))
for page in range(1,4):
    c.setFont('Helvetica',26);c.drawString(50,770,f'SHIYE V5.0.1 / PAGE {page}')
    c.setFont('Helvetica',14)
    for line in range(18): c.drawString(50,710-line*28,f'Preview / search / export test line {line+1}')
    if page==1: c.acroForm.textfield(name='auditName',x=50,y=90,width=280,height=30,value='Original value')
    c.showPage()
c.save()
im=Image.new('RGB',(1300,1800),'white');d=ImageDraw.Draw(im);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',48)
for i,text in enumerate(['拾页 V5.0.1 测试文档','离线文字识别与可搜索PDF','编号 501 金额 1234.56','此文件仅为自动测试样本','保存和分享应保留真实内容']): d.text((100,180+i*170),text,fill='black',font=font)
d.rectangle((55,55,1245,1745),outline='gray',width=6);im.save(out/'ShiYeAudit501.jpg',quality=95)
print(out)
