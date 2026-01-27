package com.krishna.netspeedlite

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

import androidx.activity.enableEdgeToEdge

class TermsPrivacyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val TYPE_TERMS = "terms"
        const val TYPE_PRIVACY = "privacy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_terms_privacy)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val btnBack = findViewById<android.widget.ImageButton>(R.id.btnBack)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvLastUpdated = findViewById<TextView>(R.id.tvLastUpdated)
        val textContent = findViewById<TextView>(R.id.textContent)

        // Set up toolbar (no action bar navigation needed)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        
        // Custom back button handler
        btnBack.setOnClickListener { finish() }

        tvTitle.text = getString(R.string.privacy_policy)
        textContent.text = Html.fromHtml(getPrivacyBody(), Html.FROM_HTML_MODE_COMPACT)
        
        // Dynamic last updated date
        tvLastUpdated.text = getString(R.string.last_updated_value)
        
        // Make links clickable and set link color explicitly
        textContent.movementMethod = LinkMovementMethod.getInstance()
        textContent.setLinkTextColor(android.graphics.Color.parseColor("#03A9F4"))
    }

    private fun getPrivacyBody(): String {
        return """
            <p>Welcome to <strong>Netspeed Lite</strong>. We respect your privacy and are committed to protecting it. This policy explains what data our app accesses and how it is used.</p>
            <br>

            <h4 style="color:#2196F3">1. Data Collection &amp; Usage</h4>
            <ul>
                <li><strong>No Personal Data:</strong> We do not collect, store, or share any personally identifiable information (PII), browsing history, or IP addresses.</li>
                <li><strong>Network Usage Stats:</strong> We use the <em>Usage Access</em> permission solely to read your device's historical network traffic logs. This processing happens 100% locally on your device.</li>
                <li><strong>Local Storage:</strong> Your preferences (theme, data limits) and manually tracked data counters are stored locally using Android SharedPreferences. This data never leaves your device and is automatically deleted if you uninstall the app.</li>
            </ul>
            <br>

            <h4 style="color:#2196F3">2. Permissions</h4>
            <p>This app requires the following permissions to function:</p>
            <ul>
                <li><strong>INTERNET:</strong> Required for the speed meter to measure your active network throughput.</li>
                <li><strong>ACCESS_NETWORK_STATE:</strong> Required to detect whether you are connected to mobile data or Wi-Fi.</li>
                <li><strong>ACCESS_WIFI_STATE:</strong> Required to read your Wi-Fi signal strength for the optional Wi-Fi indicator feature.</li>
                <li><strong>FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE:</strong> Required to keep the speed meter running persistently in the status bar notification.</li>
                <li><strong>PACKAGE_USAGE_STATS (Usage Access):</strong> Required to display historical data usage (Today, Last 7 Days, This Month).</li>
                <li><strong>POST_NOTIFICATIONS:</strong> Required to show the live speed indicator notification.</li>
                <li><strong>RECEIVE_BOOT_COMPLETED:</strong> Required to automatically restart the speed meter after device reboot (if enabled in settings).</li>
                <li><strong>REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:</strong> Optionally requested to ensure the speed meter runs reliably without being killed by the system.</li>
            </ul>
            <br>

            <h4 style="color:#2196F3">3. Third-Party Services</h4>
            <ul>
                <li><strong>Google Play In-App Review API:</strong> We may use the Google Play In-App Review API to prompt you for a rating. This SDK is provided by Google and may send anonymized identifiers to Google. No personal data is shared by us. Please refer to <a href="https://policies.google.com/privacy">Google's Privacy Policy</a> for details.</li>
            </ul>
            <br>

            <h4 style="color:#2196F3">4. Data Retention</h4>
            <p>All data is stored locally on your device. We do not retain any data on external servers. Your preferences and usage history are automatically deleted when you uninstall the app.</p>
            <br>

            <h4 style="color:#2196F3">5. Children's Privacy</h4>
            <p>Netspeed Lite is not directed at children under the age of 13. We do not knowingly collect any information from children. If you believe a child has provided us with personal information, please contact us so we can take corrective action.</p>
            <br>

            <h4 style="color:#2196F3">6. Your Rights (GDPR/CCPA)</h4>
            <p>Since no personal data is collected or transmitted by Netspeed Lite, there is no personal data for us to access, modify, or delete on our end. All data resides solely on your device, giving you full control. You can clear app data or uninstall the app at any time to remove all stored information.</p>
            <br>

            <h4 style="color:#2196F3">7. Disclaimer</h4>
            <p><strong>Netspeed Lite</strong> is provided "AS IS". The data usage statistics provided by this app are estimates based on your device's internal logs and interpolation algorithms. These figures may differ from your mobile carrier's official billing records. We do not guarantee 100% accuracy and are not liable for any data overage charges incurred.</p>
            <br>

            <h4 style="color:#2196F3">8. Changes to This Policy</h4>
            <p>We may update this Privacy Policy from time to time. Any changes will be reflected with a new "Last Updated" date within the app. Continued use of the app after changes constitutes acceptance of the revised policy.</p>
            <br>

            <h4 style="color:#2196F3">9. Contact Us</h4>
            <p>If you have questions about this policy, please contact us at <a href="mailto:contact.krishna.apps@gmail.com"><b><font color="#03A9F4">contact.krishna.apps@gmail.com</font></b></a>.</p>
        """.trimIndent()
    }
}
