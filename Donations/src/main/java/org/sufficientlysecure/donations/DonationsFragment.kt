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

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.sufficientlysecure.donations.util.GoogleIABHelper
import org.sufficientlysecure.donations.util.GoogleIABListener

class DonationsFragment : Fragment() {

    private var mGoogleSpinner: Spinner? = null

    // Google Play helper object
    private var mHelper: GoogleIABHelper? = null

    private var mDebug = false

    // Triple<googlePrivateKey, Map<catalogItems, catalogValues>, googleCutPercent>
    private var mGoogle: Triple<String, Map<String, String>, Int>? = null
    // Triple<email, currencyCode, itemName>
    private var mPaypal: Triple<String, String, String>? = null
    // address
    private var mBitcoinAddress: String? = null

    // Callback for when a purchase is finished
    private val mGoogleCallback = object : GoogleIABListener {
        override fun donationFailed() {
            Log.e(TAG, "Donation failed")
            if (isAdded)
                openDialog(R.drawable.alert,
                        R.string.donations__google_android_market_not_supported_title,
                        getString(R.string.donations__google_android_market_not_supported))
            else
                Log.e(TAG, "Not attached to activity")
        }
        override fun donationSuccess(productId: String) {
            if (mDebug)
                Log.d(TAG, "Purchase finished: $productId")

            if (isAdded) {
                // show thanks openDialog
                openDialog(R.drawable.briefcase_check, R.string.donations__thanks_dialog_title,
                        getString(R.string.donations__thanks_dialog))
            } else
                Log.e(TAG, "Not attached to activity")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mDebug = requireArguments().getBoolean(ARG_DEBUG)
        if (requireArguments().getBoolean(ARG_GOOGLE_ENABLED)) {
            val catalogValues = requireArguments().getStringArray(ARG_GOOGLE_CATALOG_VALUES)!!
            mGoogle = Triple(
                    requireArguments().getString(ARG_GOOGLE_PUBKEY)!!,
                    requireArguments().getStringArray(ARG_GOOGLE_CATALOG)!!.withIndex().associateBy ({it.value}, {catalogValues[it.index]}),
                    requireArguments().getInt(ARG_GOOGLE_CUT_PERCENT)
            )
        }

        if (requireArguments().getBoolean(ARG_PAYPAL_ENABLED))
            mPaypal = Triple(
                    requireArguments().getString(ARG_PAYPAL_USER)!!,
                    requireArguments().getString(ARG_PAYPAL_CURRENCY_CODE)!!,
                    requireArguments().getString(ARG_PAYPAL_ITEM_NAME)!!
            )

        if (requireArguments().getBoolean(ARG_BITCOIN_ENABLED))
            mBitcoinAddress = requireArguments().getString(ARG_BITCOIN_ADDRESS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.donations__fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var worked = false

        /* Google */
        mGoogle?.let {
            worked = true
            val googleViewStub = requireView().findViewById<ViewStub>(R.id.donations__google_stub)
            val googleView = googleViewStub.inflate()

            // display cut percentage
            googleView.findViewById<TextView>(R.id.donations__google_android_market_description).text = getString(R.string.donations__google_android_market_description, it.third)

            // choose donation amount
            mGoogleSpinner = googleView.findViewById(
                    R.id.donations__google_android_market_spinner)
            val adapter = if (mDebug) {
                ArrayAdapter(requireActivity(),
                        android.R.layout.simple_spinner_item, CATALOG_DEBUG)
            } else {
                ArrayAdapter(requireActivity(),
                        android.R.layout.simple_spinner_item, it.second.values.toTypedArray())
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            mGoogleSpinner!!.adapter = adapter

            val btGoogle = googleView.findViewById<Button>(
                    R.id.donations__google_android_market_donate_button)
            btGoogle.setOnClickListener { _ ->
                try {
                    donateGoogleOnClick(it.second.keys.toList())
                } catch (e: IllegalStateException) {     // In some devices, it is impossible to setup IAB Helper
                    if (mDebug)
                    // and this exception is thrown, being almost "impossible"
                        Log.e(TAG, e.message ?: "Error!?")     // to the user to control it and forcing app close.
                    openDialog(R.drawable.alert,
                            R.string.donations__google_android_market_not_supported_title,
                            getString(R.string.donations__google_android_market_not_supported))
                }
            }
        }

        /* PayPal */
        mPaypal?.let {
            worked = true
            val paypalViewStub = requireView().findViewById<ViewStub>(R.id.donations__paypal_stub)
            val paypalView = paypalViewStub.inflate()

            val btPayPal = paypalView.findViewById<Button>(R.id.donations__paypal_donate_button)
            btPayPal.setOnClickListener { _ -> donatePayPalOnClick(it) }
        }

        /* Bitcoin */
        mBitcoinAddress?.let {
            worked = true
            // inflate bitcoin view into stub
            val bitcoinViewStub = requireView().findViewById<View>(R.id.donations__bitcoin_stub) as ViewStub
            val bitcoinView = bitcoinViewStub.inflate()

            val btBitcoin = bitcoinView.findViewById<Button>(R.id.donations__bitcoin_button)
            btBitcoin.setOnClickListener { _ -> donateBitcoinOnClick(it) }
            btBitcoin.setOnLongClickListener {
                val clipboard = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(mBitcoinAddress, mBitcoinAddress)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireActivity(), R.string.donations__bitcoin_toast_copy, Toast.LENGTH_SHORT).show()
                true
            }
        }

        if (!worked) {
            requireView().findViewById<TextView>(R.id.donations__not_available).visibility = View.VISIBLE
        }
    }

    /**
     * Open dialog
     */
    internal fun openDialog(icon: Int, title: Int, message: String) {
        val dialogBuilder = MaterialAlertDialogBuilder(requireActivity())

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
            mHelper = GoogleIABHelper(requireActivity(), mGoogleCallback)

        if (mDebug) {
            // when debugging, choose android.test.x item
            mHelper!!.makePayment(CATALOG_DEBUG[index])
        } else {
            mHelper!!.makePayment(catalogItems[index])
        }
    }

    /**
     * Donate button with PayPal by opening browser with defined URL For possible parameters see:
     * https://developer.paypal.com/docs/paypal-payments-standard/integration-guide/Appx-websitestandard-htmlvariables/
     */
    private fun donatePayPalOnClick(data: Triple<String, String, String>) {
        val uriBuilder = Uri.Builder()
        uriBuilder.scheme("https").authority("www.paypal.com").path("cgi-bin/webscr")
        uriBuilder.appendQueryParameter("cmd", "_donations")

        uriBuilder.appendQueryParameter("business", data.first)
        uriBuilder.appendQueryParameter("lc", "US")
        uriBuilder.appendQueryParameter("item_name", data.third)
        uriBuilder.appendQueryParameter("no_note", "1")
        uriBuilder.appendQueryParameter("no_shipping", "1")
        uriBuilder.appendQueryParameter("currency_code", data.second)
        val payPalUri = uriBuilder.build()

        if (mDebug)
            Log.d(TAG, "Opening the browser with the url: $payPalUri")

        val viewIntent = Intent(Intent.ACTION_VIEW).setData(payPalUri)

        try {
            startActivity(viewIntent)
        } catch (e: ActivityNotFoundException) {
            openDialog(R.drawable.alert, R.string.donations__alert_dialog_title,
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
            openDialog(R.drawable.alert, R.string.donations__alert_dialog_title,
                    getString(R.string.donations__alert_dialog_no_browser))        }

    }

    companion object {

        const val ARG_DEBUG = "debug"

        const val ARG_GOOGLE_ENABLED = "googleEnabled"
        const val ARG_GOOGLE_PUBKEY = "googlePubkey"
        const val ARG_GOOGLE_CATALOG = "googleCatalog"
        const val ARG_GOOGLE_CATALOG_VALUES = "googleCatalogValues"
        const val ARG_GOOGLE_CUT_PERCENT = "googleCutPercent"

        const val ARG_PAYPAL_ENABLED = "paypalEnabled"
        const val ARG_PAYPAL_USER = "paypalUser"
        const val ARG_PAYPAL_CURRENCY_CODE = "paypalCurrencyCode"
        const val ARG_PAYPAL_ITEM_NAME = "mPaypalItemName"

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
         * @param bitcoinEnabled      Enable bitcoin donations
         * @param bitcoinAddress      The address to receive bitcoin
         * @return DonationsFragment
         */
        fun newInstance(debug: Boolean, googleEnabled: Boolean, googlePubkey: String?,
                        googleCatalog: Array<String>?, googleCatalogValues: Array<String>?, googleCutPercent: Int,
                        paypalEnabled: Boolean, paypalUser: String?, paypalCurrencyCode: String?,
                        paypalItemName: String?, bitcoinEnabled: Boolean,
                        bitcoinAddress: String?): DonationsFragment {

            val donationsFragment = DonationsFragment()
            val args = Bundle()

            args.putBoolean(ARG_DEBUG, debug)

            args.putBoolean(ARG_GOOGLE_ENABLED, googleEnabled)
            args.putString(ARG_GOOGLE_PUBKEY, googlePubkey)
            args.putStringArray(ARG_GOOGLE_CATALOG, googleCatalog)
            args.putStringArray(ARG_GOOGLE_CATALOG_VALUES, googleCatalogValues)
            args.putInt(ARG_GOOGLE_CUT_PERCENT, googleCutPercent)

            args.putBoolean(ARG_PAYPAL_ENABLED, paypalEnabled)
            args.putString(ARG_PAYPAL_USER, paypalUser)
            args.putString(ARG_PAYPAL_CURRENCY_CODE, paypalCurrencyCode)
            args.putString(ARG_PAYPAL_ITEM_NAME, paypalItemName)

            args.putBoolean(ARG_BITCOIN_ENABLED, bitcoinEnabled)
            args.putString(ARG_BITCOIN_ADDRESS, bitcoinAddress)

            donationsFragment.arguments = args
            return donationsFragment
        }

        @Deprecated("flattr no longer supported", ReplaceWith("newInstance(debug, googleEnabled," +
                "googlePubkey, googleCatalog, googleCatalogValues, 30, paypalEnabled, paypalUser, " +
                "paypalCurrencyCode, paypalItemName, bitcoinEnabled, bitcoinAddress)"))
        fun newInstance(debug: Boolean, googleEnabled: Boolean, googlePubkey: String?,
                        googleCatalog: Array<String>?, googleCatalogValues: Array<String>?,
                        paypalEnabled: Boolean, paypalUser: String?, paypalCurrencyCode: String?,
                        paypalItemName: String?, flattrEnabled: Boolean, flattrProjectUrl: String?,
                        flattrUrl: String?, bitcoinEnabled: Boolean,
                        bitcoinAddress: String?): DonationsFragment {

            if (flattrEnabled || flattrProjectUrl != null || flattrUrl != null)
                Log.e(TAG, "You can't use flattr, their API is gone!")

            return newInstance(debug, googleEnabled, googlePubkey, googleCatalog,
                    googleCatalogValues, 30, paypalEnabled, paypalUser, paypalCurrencyCode,
                    paypalItemName, bitcoinEnabled, bitcoinAddress)
        }

        @Deprecated("Google cut percentage should be provided", ReplaceWith("newInstance(debug, googleEnabled," +
                "googlePubkey, googleCatalog, googleCatalogValues, 30, paypalEnabled, paypalUser, " +
                "paypalCurrencyCode, paypalItemName, bitcoinEnabled, bitcoinAddress)"))
        fun newInstance(debug: Boolean, googleEnabled: Boolean, googlePubkey: String?,
                        googleCatalog: Array<String>?, googleCatalogValues: Array<String>?,
                        paypalEnabled: Boolean, paypalUser: String?, paypalCurrencyCode: String?,
                        paypalItemName: String?, bitcoinEnabled: Boolean,
                        bitcoinAddress: String?): DonationsFragment {

            return newInstance(debug, googleEnabled, googlePubkey, googleCatalog,
                    googleCatalogValues, 30, paypalEnabled, paypalUser, paypalCurrencyCode,
                    paypalItemName, bitcoinEnabled, bitcoinAddress)
        }
    }
}
