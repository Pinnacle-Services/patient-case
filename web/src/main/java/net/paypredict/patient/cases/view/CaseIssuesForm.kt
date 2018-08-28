package net.paypredict.patient.cases.view

import com.vaadin.flow.component.Composite
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import net.paypredict.patient.cases.data.DBS
import net.paypredict.patient.cases.data.doc
import net.paypredict.patient.cases.data.opt
import net.paypredict.patient.cases.data.worklist.*
import net.paypredict.patient.cases.pokitdok.client.ApiException
import net.paypredict.patient.cases.pokitdok.client.EligibilityQuery
import net.paypredict.patient.cases.pokitdok.client.digest
import net.paypredict.patient.cases.pokitdok.client.query
import org.bson.Document
import java.io.IOException
import kotlin.properties.Delegates

/**
 * <p>
 * Created by alexei.vylegzhanin@gmail.com on 8/15/2018.
 */
class CaseIssuesForm : Composite<Div>() {
    var value: CaseStatus? by Delegates.observable(null) { _, _: CaseStatus?, new: CaseStatus? ->
        accession.value = new?.accession ?: ""
        claim.value = new?.claim ?: ""

        val caseIssues = new?.let {
            DBS.Collections.casesIssues().find(Document("_id", it._id)).firstOrNull()?.toCaseIssues()
        }
        issuesNPI.value = caseIssues?.npi
        issuesEligibility.value = caseIssues?.eligibility
        issuesAddress.value = caseIssues?.address
        issuesExpert.value = caseIssues?.expert
    }

    private val accession = TextField("Accession").apply {
        isReadOnly = true
    }
    private val claim = TextField("Claim ID").apply {
        isReadOnly = true
    }

    private val issuesNPI = IssuesFormGrid(IssueNPI)
    private val issuesEligibility = IssuesFormGrid(IssueEligibility) { openEligibilityDialog(it) }
    private val issuesAddress = IssuesFormGrid(IssueAddress)
    private val issuesExpert = IssuesFormNote(IssueExpert)

    init {
        content.setSizeFull()
        content.style["overflow"] = "auto"
        content += VerticalLayout().apply {
            setSizeUndefined()
            this += HorizontalLayout().apply {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                this += Span("Issues:").apply { style["font-weight"] = "bold" }
                this += accession
                this += claim
            }
            this += VerticalLayout(issuesNPI, issuesEligibility, issuesAddress, issuesExpert).apply {
                isPadding = false
                height = null
            }
        }
    }

    private fun openEligibilityDialog(eligibility: IssueEligibility) {
        Dialog().apply {
            width = "70vw"
            this += PatientEligibilityForm().apply {
                setSizeFull()
                isPadding = false
                value = eligibility
                checkPatientEligibility = { issue: IssueEligibility ->
                    try {
                        checkEligibility(issue)
                        close()
                    } catch (e: Throwable) {
                        val error = when (e) {
                            is ApiException ->
                                Document
                                    .parse(e.responseJson.toString())
                                    .opt<String>("data", "errors", "query")
                                    ?: e.message
                            else ->
                                e.message
                        }
                        Dialog().apply {
                            this += VerticalLayout().apply {
                                this += H2("API Call Error")
                                this += H3(error)
                            }
                            open()
                        }
                    }
                }
            }
            open()
        }
    }

    private fun checkEligibility(issue: IssueEligibility) {
        val insurancePayerId = issue.insurance?.payerId ?: throw AssertionError("insurance payerId is required")
        val tradingPartners = DBS.Collections.tradingPartners()
        val tradingPartnerId: String =
            tradingPartners
                .find(doc { doc["data.payer_id"] = insurancePayerId })
                .firstOrNull()
                ?.opt<String>("_id")
                ?: tradingPartners
                    .find(doc { doc["custom.payer_id"] = insurancePayerId })
                    .firstOrNull()
                    ?.opt<String>("_id")
                ?: throw IOException("insurance payerId $insurancePayerId not found in tradingPartners")

        val query = EligibilityQuery(
            member = EligibilityQuery.Member(
                first_name = issue.subscriber!!.firstName!!,
                last_name = issue.subscriber!!.lastName!!,
                birth_date = issue.subscriber!!.dobAsLocalDate!! formatAs EligibilityQuery.Member.dateFormat,
                id = issue.subscriber!!.policyNumber!!
            ),
            provider = EligibilityQuery.Provider(
                organization_name = "SAGIS, PLLC",
                npi = "1548549066"
            ),
            trading_partner_id = tradingPartnerId
        )

        val digest = query.digest()
        val collection = DBS.Collections.eligibility()
        collection
            .find(doc { doc["_id"] = digest }).firstOrNull()
            ?: query.query { Document.parse(it.readText()) }.let { response ->
                doc {
                    doc["_id"] = digest
                    doc["data"] = response["data"]
                    doc["meta"] = response["meta"]
                }.also {
                    collection.insertOne(it)
                }

            }
    }
}