# NetSpeed Lite 🚀

NetSpeed Lite is a lightweight Android application that helps users monitor **real-time internet speed** and **data usage** in a simple, clean, and battery-friendly way.  
The app focuses on **clarity, accuracy, and minimal UI**, making it ideal for everyday use.

---

## 📱 Features

- 📊 **Real-time Internet Speed**
  - Shows live **download** and **upload** speed
  - Updates continuously while internet is active

- 📈 **Data Usage Monitoring**
  - Daily data usage
  - Weekly data usage
  - Monthly data usage

- 🔔 **Notification Bar Speed Monitor**
  - Live speed shown directly in the notification bar
  - Runs efficiently in the background

- 🎨 **Clean & Minimal UI**
  - Easy-to-read layout
  - Modern Material Design components
  - Light and Dark mode support (if enabled)

- ⚡ **Lightweight & Battery Efficient**
  - Minimal background processing
  - Optimized for low RAM and low power usage

---

## 🛠️ Tech Stack

- **Language:** Kotlin  
- **UI:** XML (Material Design)  
- **Architecture:** Simple Activity-based / MVVM (as applicable)  
- **Minimum SDK:** 23  
- **Target SDK:** Latest Android version  

---

## 🚀 How It Works

1. The app reads network statistics using Android system APIs  
2. Calculates upload/download speed in real time  
3. Displays speed on the main screen and notification bar  
4. Aggregates data usage for daily, weekly, and monthly views  

---

## 🔐 Permissions Used

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>


