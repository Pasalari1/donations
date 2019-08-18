/*
 * Copyright (C) 2011-2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2019 "Hackintosh 5" <hackintoshfive@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sufficientlysecure.donations

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.util.Log
import android.util.Pair
import android.view.*
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.sufficientlysecure.donations.util.GoogleIABHelper
import org.sufficientlysecure.donations.util.GoogleIABListener

class DonationsFragment : Fragment() {

    private var mGoogleSpinner: Spinner? = null

    // Google Play helper object
    private var mHelper: GoogleIABHelper? = null

    private var mDebug = false

    // Pair<googlePrivateKey, Map<catalogItems, catalogValues>>
    private var mGoogle: Pair<String, Map<String, String>>? = null
    // Triple<email, currencyCode, itemName>
    private var mPaypal: Triple<String, String, String>? = null
    // Pair<projectUrl, url>
    private var mFlattr: Pair<String, String>? = null
    // address
    private var mBitcoinAddress: String? = null

    // Callback for when a purchase is finished
    private val mGoogleCallback = object : GoogleIABListener {
        override fun donationFailed() {
            Log.e(TAG, "Donation failed")
            if (isAdded())
                openDialog(android.R.drawable.ic_dialog_alert,
                        R.string.donations__google_android_market_not_supported_title,
                        getString(R.string.donations__google_android_market_not_supported))
            else
                Log.e(TAG, "Not attached to activity")
        }
        override fun donationSuccess(productId: String) {
            if (mDebug)
                Log.d(TAG, "Purchase finished: $productId")

            if (isAdded()) {
                // show thanks openDialog
                openDialog(android.R.drawable.ic_dialog_info, R.string.donations__thanks_dialog_title,
                        getString(R.string.donations__thanks_dialog))
            } else
                Log.e(TAG, "Not attached to activity")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assert(arguments != null)

        mDebug = arguments!!.getBoolean(ARG_DEBUG)
        if (arguments!!.getBoolean(ARG_GOOGLE_ENABLED)) {
            val catalogValues = arguments!!.getStringArray(ARG_GOOGLE_CATALOG_VALUES)!!
            mGoogle = Pair(arguments!!.getString(ARG_GOOGLE_PUBKEY)!!,
                    arguments!!.getStringArray(ARG_GOOGLE_CATALOG)!!.withIndex()
                            .associateBy ({it.value}, {catalogValues[it.index]}))
        }

        if (arguments!!.getBoolean(ARG_PAYPAL_ENABLED))
            mPaypal = Triple(arguments!!.getString(ARG_PAYPAL_USER)!!,
                    arguments!!.getString(ARG_PAYPAL_CURRENCY_CODE)!!,
                    arguments!!.getString(ARG_PAYPAL_ITEM_NAME)!!)

        if (arguments!!.getBoolean(ARG_FLATTR_ENABLED))
            mFlattr = Pair(arguments!!.getString(ARG_FLATTR_PROJECT_URL)!!,
                    arguments!!.getString(ARG_FLATTR_URL)!!)

        if (arguments!!.getBoolean(ARG_BITCOIN_ENABLED))
            mBitcoinAddress = arguments!!.getString(ARG_BITCOIN_ADDRESS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.donations__fragment, container, false)
    }

    @TargetApi(11)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        /* Flattr */
        mFlattr?.let {
            val flattrViewStub = activity!!.findViewById<ViewStub>(R.id.donations__flattr_stub)
            flattrViewStub.inflate()
            buildFlattrView(it.first, it.second)
        }

        /* Google */
        mGoogle?.let {
            val googleViewStub = activity!!.findViewById<ViewStub>(R.id.donations__google_stub)
            googleViewStub.inflate()

            // choose donation amount
            mGoogleSpinner = activity!!.findViewById(
                    R.id.donations__google_android_market_spinner)
            val adapter = if (mDebug) {
                ArrayAdapter(activity!!,
                        android.R.layout.simple_spinner_item, CATALOG_DEBUG)
            } else {
                ArrayAdapter(activity!!,
                        android.R.layout.simple_spinner_item, it.second.values.toTypedArray())
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            mGoogleSpinner!!.adapter = adapter

            val btGoogle = activity!!.findViewById<Button>(
                    R.id.donations__google_android_market_donate_button)
            btGoogle.setOnClickListener { _ ->
                try {
                    donateGoogleOnClick(it.second.values.toList())
                } catch (e: IllegalStateException) {     // In some devices, it is impossible to setup IAB Helper
                    if (mDebug)
                    // and this exception is thrown, being almost "impossible"
                        Log.e(TAG, e.message ?: "Error!?")     // to the user to control it and forcing app close.
                    openDialog(android.R.drawable.ic_dialog_alert,
                            R.string.donations__google_android_market_not_supported_title,
                            getString(R.string.donations__google_android_market_not_supported))
                }
            }

            // Create the helper, passing it our context and the public key to verify signatures with
            if (mDebug)
                Log.d(TAG, "Creating IAB helper.")
        }

        /* PayPal */
        mPaypal?.let {
            val paypalViewStub = activity!!.findViewById<ViewStub>(R.id.donations__paypal_stub)
            paypalViewStub.inflate()

            val btPayPal = activity!!.findViewById<Button>(R.id.donations__paypal_donate_button)
            btPayPal.setOnClickListener { _ -> donatePayPalOnClick(it) }
        }

        /* Bitcoin */
        mBitcoinAddress?.let {
            // inflate bitcoin view into stub
            val bitcoinViewStub = activity!!.findViewById<View>(R.id.donations__bitcoin_stub) as ViewStub
            bitcoinViewStub.inflate()

            val btBitcoin = activity!!.findViewById<Button>(R.id.donations__bitcoin_button)
            btBitcoin.setOnClickListener { _ -> donateBitcoinOnClick(it) }
            btBitcoin.setOnLongClickListener {
                val clipboard = activity!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(mBitcoinAddress, mBitcoinAddress)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, R.string.donations__bitcoin_toast_copy, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    /**
     * Open dialog
     */
    internal fun openDialog(icon: Int, title: Int, message: String) {
        val dialogBuilder = AlertDialog.Builder(activity)
        dialogBuilder.setIcon(icon)
        dialogBuilder.setTitle(title)
        dialogBuilder.setMessage(message)
        dialogBuilder.setCancelable(true)
        dialogBuilder.setNeutralButton(R.string.donations__button_close
        ) { dialog, _ -> dialog.dismiss() }
        dialogBuilder.show()
    }

    /**
     * Donate button executes donations based on selection in spinner
     */
    private fun donateGoogleOnClick(catalogItems: List<String>) {
        val index = mGoogleSpinner!!.selectedItemPosition
        if (mDebug)
            Log.d(TAG, "selected item in spinner: $index")

        if (mHelper == null)
            mHelper = GoogleIABHelper(activity!!, mGoogleCallback)

        if (mDebug) {
            // when debugging, choose android.test.x item
            mHelper!!.makePayment(CATALOG_DEBUG[index])
        } else {
            mHelper!!.makePayment(catalogItems[index])
        }
    }

    /**
     * Donate button with PayPal by opening browser with defined URL For possible parameters see:
     * https://developer.paypal.com/webapps/developer/docs/classic/paypal-payments-standard/integration-guide/Appx_websitestandard_htmlvariables/
     */
    private fun donatePayPalOnClick(data: Triple<String, String, String>) {
        val uriBuilder = Uri.Builder()
        uriBuilder.scheme("https").authority("www.paypal.com").path("cgi-bin/webscr")
        uriBuilder.appendQueryParameter("cmd", "_donations")

        uriBuilder.appendQueryParameter("business", data.first)
        uriBuilder.appendQueryParameter("lc", "US")
        uriBuilder.appendQueryParameter("item_name", data.third)
        uriBuilder.appendQueryParameter("no_note", "1")
        // uriBuilder.appendQueryParameter("no_note", "0");
        // uriBuilder.appendQueryParameter("cn", "Note to the developer");
        uriBuilder.appendQueryParameter("no_shipping", "1")
        uriBuilder.appendQueryParameter("currency_code", data.second)
        val payPalUri = uriBuilder.build()

        if (mDebug)
            Log.d(TAG, "Opening the browser with the url: $payPalUri")

        val viewIntent = Intent(Intent.ACTION_VIEW, payPalUri)
        // force intent chooser, do not automatically use PayPal app
        // https://github.com/PrivacyApps/donations/issues/28
        val title = resources.getString(R.string.donations__paypal)
        val chooser = Intent.createChooser(viewIntent, title)

        if (viewIntent.resolveActivity(activity!!.packageManager) != null) {
            startActivity(chooser)
        } else {
            openDialog(android.R.drawable.ic_dialog_alert, R.string.donations__alert_dialog_title,
                    getString(R.string.donations__alert_dialog_no_browser))
        }
    }

    /**
     * Donate with bitcoin by opening a bitcoin: intent if available.
     */
    private fun donateBitcoinOnClick(address: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse("bitcoin:$address")

        if (mDebug)
            Log.d(TAG, "Attempting to donate bitcoin using URI: " + i.dataString!!)

        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            openDialog(android.R.drawable.ic_dialog_alert, R.string.donations__alert_dialog_title,
                    getString(R.string.donations__alert_dialog_no_browser))        }

    }

    /**
     * Build view for Flattr. see Flattr API for more information:
     * http://developers.flattr.net/button/
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "SetTextI18n")
    @TargetApi(11)
    private fun buildFlattrView(projectUrl: String, url: String) {
        val mLoadingFrame = activity!!.findViewById<FrameLayout>(R.id.donations__loading_frame)
        val mFlattrWebview = activity!!.findViewById<WebView>(R.id.donations__flattr_webview)

        // disable hardware acceleration for this webview to get transparent background working
        mFlattrWebview.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        // define own webview client to override loading behaviour
        mFlattrWebview.webViewClient = object : WebViewClient() {
            /**
             * Open all links in browser, not in webview
             */
            override fun shouldOverrideUrlLoading(view: WebView, urlNewString: String): Boolean {
                try {
                    view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(urlNewString)))
                } catch (e: ActivityNotFoundException) {
                    openDialog(android.R.drawable.ic_dialog_alert, R.string.donations__alert_dialog_title,
                            getString(R.string.donations__alert_dialog_no_browser))
                }

                return false
            }

            /**
             * Support N properly
             * https://stackoverflow.com/a/38484061/5509575
             */
            @TargetApi(Build.VERSION_CODES.N)
            override fun shouldOverrideUrlLoading(view: WebView, urlNewRequest: WebResourceRequest): Boolean {
                try {
                    view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, urlNewRequest.url))
                } catch (e: ActivityNotFoundException) {
                    openDialog(android.R.drawable.ic_dialog_alert, R.string.donations__alert_dialog_title,
                            getString(R.string.donations__alert_dialog_no_browser))
                }

                return false
            }

            /**
             * Links in the flattr iframe should load in the browser not in the iframe itself,
             * http:/
             * /stackoverflow.com/questions/5641626/how-to-get-webview-iframe-link-to-launch-the
             * -browser
             */
            override fun onLoadResource(view: WebView, url: String) {
                if (url.contains("flattr")) {
                    val result = view.hitTestResult
                    if (result != null && result.type > 0) {
                        try {
                            view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            openDialog(android.R.drawable.ic_dialog_alert, R.string.donations__alert_dialog_title,
                                    getString(R.string.donations__alert_dialog_no_browser))
                        }

                        view.stopLoading()
                    }
                }
            }

            /**
             * After loading is done, remove frame with progress circle
             */
            override fun onPageFinished(view: WebView, url: String) {
                // remove loading frame, show webview
                if (mLoadingFrame.visibility == View.VISIBLE) {
                    mLoadingFrame.visibility = View.GONE
                    mFlattrWebview.visibility = View.VISIBLE
                }
            }
        }

        // make text white and background transparent
        val htmlStart = "<html> <head><style type='text/css'>*{color: #FFFFFF; background-color: transparent;}</style>"

        // set url of flattr link
        val mFlattrUrlTextView = activity!!.findViewById<TextView>(R.id.donations__flattr_url)
        mFlattrUrlTextView.text = "https://$url"

        val flattrJavascript = ("<script type='text/javascript'>"
                + "/* <![CDATA[ */"
                + "(function() {"
                + "var s = document.createElement('script'), t = document.getElementsByTagName('script')[0];"
                + "s.type = 'text/javascript';" + "s.async = true;" + "s.src = '"
                + "https://api.flattr.com/js/0.6/load.js?mode=auto';" + "t.parentNode.insertBefore(s, t);"
                + "})();" + "/* ]]> */" + "</script>")
        val htmlMiddle = "</head> <body> <div align='center'>"
        val flattrHtml = ("<a class='FlattrButton' style='display:none;' href='"
                + projectUrl
                + "' target='_blank'></a> <noscript><a href='https://"
                + url
                + "' target='_blank'> <img src='https://api.flattr.com/button/flattr-badge-large.png' alt='Flattr this' title='Flattr this' border='0' /></a></noscript>")
        val htmlEnd = "</div> </body> </html>"

        val flattrCode = htmlStart + flattrJavascript + htmlMiddle + flattrHtml + htmlEnd

        mFlattrWebview.settings.javaScriptEnabled = true

        mFlattrWebview.loadData(flattrCode, "text/html", "utf-8")

        // disable scroll on touch
        mFlattrWebview.setOnTouchListener { _, motionEvent ->
            // already handled (returns true) when moving
            motionEvent.action == MotionEvent.ACTION_MOVE
        }

        // make background of webview transparent
        // has to be called AFTER loadData
        // http://stackoverflow.com/questions/5003156/android-webview-style-background-colortransparent-ignored-on-android-2-2
        mFlattrWebview.setBackgroundColor(0x00000000)
    }

    companion object {

        const val ARG_DEBUG = "debug"

        const val ARG_GOOGLE_ENABLED = "googleEnabled"
        const val ARG_GOOGLE_PUBKEY = "googlePubkey"
        const val ARG_GOOGLE_CATALOG = "googleCatalog"
        const val ARG_GOOGLE_CATALOG_VALUES = "googleCatalogValues"

        const val ARG_PAYPAL_ENABLED = "paypalEnabled"
        const val ARG_PAYPAL_USER = "paypalUser"
        const val ARG_PAYPAL_CURRENCY_CODE = "paypalCurrencyCode"
        const val ARG_PAYPAL_ITEM_NAME = "mPaypalItemName"

        const val ARG_FLATTR_ENABLED = "flattrEnabled"
        const val ARG_FLATTR_PROJECT_URL = "flattrProjectUrl"
        const val ARG_FLATTR_URL = "flattrUrl"

        const val ARG_BITCOIN_ENABLED = "bitcoinEnabled"
        const val ARG_BITCOIN_ADDRESS = "bitcoinAddress"

        private const val TAG = "Donations Library"

        // http://developer.android.com/google/play/billing/billing_testing.html
        private val CATALOG_DEBUG = arrayOf("android.test.purchased", "android.test.canceled", "android.test.refunded", "android.test.item_unavailable", "android.test.this_does_not_exist")

        /**
         * Instantiate DonationsFragment.
         *
         * @param debug               You can use BuildConfig.DEBUG to propagate the debug flag from your app to the Donations library
         * @param googleEnabled       Enabled Google Play donations
         * @param googlePubkey        Your Google Play public key
         * @param googleCatalog       Possible item names that can be purchased from Google Play
         * @param googleCatalogValues Values for the names
         * @param paypalEnabled       Enable PayPal donations
         * @param paypalUser          Your PayPal email address
         * @param paypalCurrencyCode  Currency code like EUR. See here for other codes:
         * https://developer.paypal.com/webapps/developer/docs/classic/api/currency_codes/#id09A6G0U0GYK
         * @param paypalItemName      Display item name on PayPal, like "Donation for NTPSync"
         * @param flattrEnabled       Enable Flattr donations
         * @param flattrProjectUrl    The project URL used on Flattr
         * @param flattrUrl           The Flattr URL to your thing. NOTE: Enter without http://
         * @param bitcoinEnabled      Enable bitcoin donations
         * @param bitcoinAddress      The address to receive bitcoin
         * @return DonationsFragment
         */
        fun newInstance(debug: Boolean, googleEnabled: Boolean, googlePubkey: String?, googleCatalog: Array<String>?,
                        googleCatalogValues: Array<String>?, paypalEnabled: Boolean, paypalUser: String?,
                        paypalCurrencyCode: String?, paypalItemName: String?, flattrEnabled: Boolean,
                        flattrProjectUrl: String?, flattrUrl: String?, bitcoinEnabled: Boolean, bitcoinAddress: String?): DonationsFragment {
            val donationsFragment = DonationsFragment()
            val args = Bundle()

            args.putBoolean(ARG_DEBUG, debug)

            args.putBoolean(ARG_GOOGLE_ENABLED, googleEnabled)
            args.putString(ARG_GOOGLE_PUBKEY, googlePubkey)
            args.putStringArray(ARG_GOOGLE_CATALOG, googleCatalog)
            args.putStringArray(ARG_GOOGLE_CATALOG_VALUES, googleCatalogValues)

            args.putBoolean(ARG_PAYPAL_ENABLED, paypalEnabled)
            args.putString(ARG_PAYPAL_USER, paypalUser)
            args.putString(ARG_PAYPAL_CURRENCY_CODE, paypalCurrencyCode)
            args.putString(ARG_PAYPAL_ITEM_NAME, paypalItemName)

            args.putBoolean(ARG_FLATTR_ENABLED, flattrEnabled)
            args.putString(ARG_FLATTR_PROJECT_URL, flattrProjectUrl)
            args.putString(ARG_FLATTR_URL, flattrUrl)

            args.putBoolean(ARG_BITCOIN_ENABLED, bitcoinEnabled)
            args.putString(ARG_BITCOIN_ADDRESS, bitcoinAddress)

            donationsFragment.arguments = args
            return donationsFragment
        }
    }
}
