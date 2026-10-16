# الخطوات الأخيرة (يجب تنفيذها يدوياً بسبب المصادقة)

## 1. دفع الكود إلى GitHub
# الأمر التالي يجب تشغيله في الجهاز المحلي أو بعد إدخال رمز GitHub:
# (هذا المستودع موجود بالفعل في هذا المشروع: https://github.com/ahmadmohammad02/galaxy)
git push origin main

## 2. تشغيل البناء عن بُعد (دون Mac)
# بعد الدفع، اذهب إلى:
# https://github.com/ahmadmohammad02/galaxy/actions
# ثم اضغط على زر "Run workflow" في "Build Galaxy TV Apple"

# أو عبر سطر الأوامر (إذا كان gh مثبتاً ومصادقاً):
gh workflow run build-apple.yml --repo ahmadmohammad02/galaxy

## 3. تحميل الناتج
# بعد اكتمال البناء:
# - GalaxyTV-iOS.app
# - GalaxyTV-tvOS.app
