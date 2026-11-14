package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.firebase.Formatters
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.utils.PdfGenerator
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt

@Composable
fun StaffAndSalaryScreen(
    shop: Shop,
    staff: List<StaffMember>,
    attendance: List<AttendanceRecord>,
    advances: List<StaffAdvance>,
    salaryMonths: List<SalaryMonthRecord>,
    activityLogs: List<ActivityLog>,
    onSaveStaff: (StaffMember) -> Unit,
    onMarkAttendance: (String, StaffMember, String, String?, Boolean, String) -> Unit,
    onClearAttendanceForDate: (String, String) -> Unit,
    onUpdateShop: (Shop, String) -> Unit,
    onSaveAdvance: (StaffAdvance) -> Unit,
    onFinalizeSalaryMonth: (SalaryMonthRecord, StaffMember?, Boolean) -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0=Attendance, 1=Salary, 2=Advances, 3=Directory
    var selectedDateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val selectedDateKey = remember(selectedDateMillis) { Formatters.dateKey(selectedDateMillis) }

    var monthOffset by remember { mutableStateOf(0) }
    val selectedMonthCal = remember(monthOffset) {
        Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }
    }
    val selectedMonthKey = remember(selectedMonthCal) { Formatters.monthKey(selectedMonthCal.timeInMillis) }
    val daysInMonth = remember(selectedMonthCal) { selectedMonthCal.getActualMaximum(Calendar.DAY_OF_MONTH) }

    var showStaffDialog by remember { mutableStateOf(false) }
    var editingStaff by remember { mutableStateOf<StaffMember?>(null) }
    var showAdvanceDialog by remember { mutableStateOf(false) }
    var showAuditLogDialog by remember { mutableStateOf(false) }

    // Secret code prompt state
    var pendingAttendanceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingSecretPromptTitle by remember { mutableStateOf("Verify Secret Code") }
    var secretActionNeedsCallbackWithCode by remember { mutableStateOf<((String) -> Unit)?>(null) }

    var generatedPdf by remember { mutableStateOf<File?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = SecondaryNavy,
                contentColor = AmberGold
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Attendance") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Salary") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Advances") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Staff (${staff.size})") })
            }

            when (tab) {
                0 -> {
                    val recordsForDate = remember(attendance, selectedDateKey) {
                        attendance.filter { it.dateKey == selectedDateKey }.associateBy { it.staffId }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            PremiumCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { selectedDateMillis -= 86_400_000L }) {
                                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day")
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Attendance Date", style = MaterialTheme.typography.labelSmall, color = SlateMuted)
                                        Text(selectedDateKey, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                    }
                                    IconButton(onClick = { selectedDateMillis += 86_400_000L }) {
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Day")
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { selectedDateMillis = System.currentTimeMillis() }) {
                                        Text("Jump to Today")
                                    }
                                    TextButton(onClick = { showAuditLogDialog = true }) {
                                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Audit Log")
                                    }
                                    TextButton(
                                        onClick = {
                                            pendingSecretPromptTitle = "Enter Secret Code to Clear $selectedDateKey"
                                            secretActionNeedsCallbackWithCode = { code ->
                                                onClearAttendanceForDate(selectedDateKey, code)
                                            }
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                                    ) {
                                        Text("Clear Day")
                                    }
                                }
                                HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Auto-Attendance (${shop.autoAttendanceTime})", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                        Text("Auto-marks unmarked active staff as Present", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    }
                                    Switch(
                                        checked = shop.autoAttendanceEnabled,
                                        onCheckedChange = { enabled ->
                                            onUpdateShop(shop.copy(autoAttendanceEnabled = enabled), if (enabled) "Auto-Attendance Enabled" else "Auto-Attendance Disabled")
                                        }
                                    )
                                }
                                if (shop.autoAttendanceEnabled && staff.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            staff.forEach { member ->
                                                if (!recordsForDate.containsKey(member.id)) {
                                                    onMarkAttendance(selectedDateKey, member, "P", null, true, "Auto-attendance ${shop.autoAttendanceTime}")
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Run Auto-Mark Now for $selectedDateKey")
                                    }
                                }
                            }
                        }

                        if (staff.isEmpty()) {
                            item {
                                EmptyStateView(
                                    title = "No Staff Added",
                                    subtitle = "Add showroom & warehouse staff to track daily attendance and monthly salary.",
                                    actionLabel = "Add Staff Member",
                                    onAction = { editingStaff = null; showStaffDialog = true }
                                )
                            }
                        } else {
                            items(staff, key = { it.id }) { member ->
                                val rec = recordsForDate[member.id]
                                val currentStatus = rec?.status ?: "UNMARKED"
                                PremiumCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(member.fullName, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                            Text("${member.designation} • ${member.salaryMethod}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                        }
                                        StatusBadge(
                                            text = currentStatus,
                                            bgColor = when (currentStatus) {
                                                "P" -> SuccessGreenSoft
                                                "HALF" -> AmberGoldSoft
                                                "A" -> DangerRedSoft
                                                "HOLIDAY" -> InfoBlueSoft
                                                else -> Color(0xFFE2E8F0)
                                            },
                                            textColor = when (currentStatus) {
                                                "P" -> SuccessGreen
                                                "HALF" -> AmberGoldDark
                                                "A" -> DangerRed
                                                "HOLIDAY" -> InfoBlue
                                                else -> SlateDark
                                            }
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf("P" to SuccessGreen, "HALF" to AmberGoldDark, "A" to DangerRed, "HOLIDAY" to InfoBlue).forEach { (st, col) ->
                                            val selected = currentStatus == st
                                            OutlinedButton(
                                                onClick = {
                                                    if (shop.secretCodeHash.isNotBlank()) {
                                                        pendingSecretPromptTitle = "Secret Code for ${member.fullName} ($st)"
                                                        secretActionNeedsCallbackWithCode = { code ->
                                                            onMarkAttendance(selectedDateKey, member, st, code, false, "Marked by owner")
                                                        }
                                                    } else {
                                                        onMarkAttendance(selectedDateKey, member, st, null, false, "Marked by owner")
                                                    }
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    containerColor = if (selected) col else Color.Transparent,
                                                    contentColor = if (selected) Color.White else col
                                                ),
                                                border = BorderStroke(1.dp, col),
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f).height(44.dp)
                                            ) {
                                                Text(st, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val monthAttendance = remember(attendance, selectedMonthKey) {
                        attendance.filter { it.monthKey == selectedMonthKey }
                    }
                    val monthAdvances = remember(advances, selectedMonthKey) {
                        advances.filter { Formatters.monthKey(it.dateMillis) == selectedMonthKey }
                    }
                    val finalizedMap = remember(salaryMonths, selectedMonthKey) {
                        salaryMonths.filter { it.monthKey == selectedMonthKey }.associateBy { it.staffId }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            PremiumCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { monthOffset-- }) {
                                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Month")
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Salary Register Month", style = MaterialTheme.typography.labelSmall, color = SlateMuted)
                                        Text(selectedMonthKey, style = MaterialTheme.typography.titleLarge, color = RoyalNavy)
                                    }
                                    IconButton(onClick = { monthOffset++ }) {
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Month")
                                    }
                                }
                            }
                        }

                        items(staff, key = { it.id }) { member ->
                            val stAtt = monthAttendance.filter { it.staffId == member.id }
                            val stAdv = monthAdvances.filter { it.staffId == member.id }
                            val savedRec = finalizedMap[member.id]

                            val present = stAtt.count { it.status == "P" }.toLong()
                            val half = stAtt.count { it.status == "HALF" }.toLong()
                            val absent = stAtt.count { it.status == "A" }.toLong()
                            val holiday = stAtt.count { it.status == "HOLIDAY" }.toLong()

                            val weeklyOffCountInMonth = remember(selectedMonthKey, member.weeklyOffDay, daysInMonth) {
                                val targetDay = when (member.weeklyOffDay.lowercase()) {
                                    "monday" -> Calendar.MONDAY
                                    "tuesday" -> Calendar.TUESDAY
                                    "wednesday" -> Calendar.WEDNESDAY
                                    "thursday" -> Calendar.THURSDAY
                                    "friday" -> Calendar.FRIDAY
                                    "saturday" -> Calendar.SATURDAY
                                    else -> Calendar.SUNDAY
                                }
                                val c = selectedMonthCal.clone() as Calendar
                                var count = 0L
                                for (d in 1..daysInMonth) {
                                    c.set(Calendar.DAY_OF_MONTH, d)
                                    if (c.get(Calendar.DAY_OF_WEEK) == targetDay) count++
                                }
                                count
                            }

                            val effectiveDailyRate = when (member.salaryMethod) {
                                "DAYS_30" -> member.monthlySalary / 30.0
                                "ACTUAL_DAYS" -> member.monthlySalary / daysInMonth.coerceAtLeast(1)
                                "WORKING_DAYS" -> {
                                    val working = (daysInMonth - weeklyOffCountInMonth).coerceAtLeast(1L)
                                    member.monthlySalary / working.toDouble()
                                }
                                else -> member.dailyRate
                            }
                            val paidDays = present + (half * 0.5) + if (member.salaryMethod != "DAILY_WAGE") holiday else 0L
                            val earned = ((paidDays * effectiveDailyRate) * 100.0).roundToInt() / 100.0
                            val advTotal = stAdv.sumOf { it.amount }
                            val arrears = savedRec?.previousArrears ?: member.previousBalance
                            val netPayable = earned + arrears - advTotal
                            val isFinalized = savedRec?.isFinalized == true

                            val computedRecord = (savedRec ?: SalaryMonthRecord()).copy(
                                monthKey = selectedMonthKey,
                                staffId = member.id,
                                staffName = member.fullName,
                                presentDays = present,
                                halfDays = half,
                                absentDays = absent,
                                weeklyOffDays = holiday,
                                monthlySalary = member.monthlySalary,
                                effectiveDailyRate = effectiveDailyRate,
                                earnedSalary = earned,
                                previousArrears = arrears,
                                advancesDeducted = advTotal,
                                netPayable = netPayable,
                                isFinalized = isFinalized
                            )

                            PremiumCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(member.fullName, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                        Text("${member.designation} • ${member.salaryMethod}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    }
                                    StatusBadge(
                                        if (isFinalized) "FINALIZED" else "OPEN",
                                        if (isFinalized) SuccessGreenSoft else AmberGoldSoft,
                                        if (isFinalized) SuccessGreen else AmberGoldDark
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Present: $present  •  Half: $half  •  Absent: $absent  •  Holiday: $holiday", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                Text("Daily Rate: ${Formatters.currency(effectiveDailyRate)}  •  Earned: ${Formatters.currency(earned)}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                Text("Prev Arrears: ${Formatters.currency(arrears)}  •  Advances: -${Formatters.currency(advTotal)}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                Spacer(Modifier.height(6.dp))
                                Text("Net Payable: ${Formatters.currency(netPayable)}", style = MaterialTheme.typography.titleMedium, color = if (netPayable >= 0) SuccessGreen else DangerRed)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            generatedPdf = PdfGenerator.generateSalaryPdf(
                                                context = context,
                                                shop = shop,
                                                staff = member,
                                                monthRecord = computedRecord,
                                                attendanceList = stAtt,
                                                advances = stAdv
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Salary PDF")
                                    }
                                    Button(
                                        onClick = { onFinalizeSalaryMonth(computedRecord, member, !isFinalized) },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isFinalized) WarningOrange else RoyalNavy),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isFinalized) "Unfinalize" else "Finalize Month")
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 84.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            PremiumCard {
                                Text("Total Staff Advances", style = MaterialTheme.typography.labelMedium, color = SlateMuted)
                                Text(Formatters.currency(advances.sumOf { it.amount }), style = MaterialTheme.typography.headlineSmall, color = DangerRed)
                            }
                        }
                        if (advances.isEmpty()) {
                            item {
                                EmptyStateView(
                                    title = "No Staff Advances",
                                    subtitle = "Record cash/UPI salary advances to automatically deduct in monthly salary.",
                                    actionLabel = "Give Advance",
                                    onAction = { showAdvanceDialog = true }
                                )
                            }
                        } else {
                            items(advances.sortedByDescending { it.dateMillis }, key = { it.id }) { adv ->
                                PremiumCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(adv.staffName, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                            Text("${Formatters.date(adv.dateMillis)} • ${adv.paymentMode}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                            if (adv.reason.isNotBlank() || adv.purpose.isNotBlank()) {
                                                Text("${adv.purpose} ${adv.reason}".trim(), style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                            }
                                        }
                                        Text(Formatters.currency(adv.amount), style = MaterialTheme.typography.titleMedium, color = DangerRed)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 84.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (staff.isEmpty()) {
                            item {
                                EmptyStateView(
                                    title = "No Staff Directory Entries",
                                    subtitle = "Add your showroom & warehouse employees here.",
                                    actionLabel = "Add Staff",
                                    onAction = { editingStaff = null; showStaffDialog = true }
                                )
                            }
                        } else {
                            items(staff, key = { it.id }) { member ->
                                PremiumCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(member.fullName, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                            Text("${member.designation} • ${member.mobile}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                            Text(
                                                if (member.salaryMethod == "DAILY_WAGE") "Daily Wage: ${Formatters.currency(member.dailyRate)}/day"
                                                else "Monthly: ${Formatters.currency(member.monthlySalary)} (${member.salaryMethod})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SlateDark
                                            )
                                            Text("Weekly Off: ${member.weeklyOffDay} • Arrears: ${Formatters.currency(member.previousBalance)}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                        }
                                        IconButton(onClick = { editingStaff = member; showStaffDialog = true }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Staff", tint = RoyalNavy)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (tab == 2 || tab == 3) {
            FloatingActionButton(
                onClick = {
                    if (tab == 2) showAdvanceDialog = true else { editingStaff = null; showStaffDialog = true }
                },
                containerColor = RoyalNavy,
                contentColor = AmberGold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("staff_fab")
            ) {
                Icon(if (tab == 2) Icons.Default.Payments else Icons.Default.PersonAdd, contentDescription = "Add")
            }
        }
    }

    if (showStaffDialog) {
        var name by remember { mutableStateOf(editingStaff?.fullName ?: "") }
        var mobile by remember { mutableStateOf(editingStaff?.mobile ?: "") }
        var designation by remember { mutableStateOf(editingStaff?.designation ?: "Salesman") }
        var method by remember { mutableStateOf(editingStaff?.salaryMethod ?: "DAYS_30") }
        var monthly by remember { mutableStateOf((editingStaff?.monthlySalary ?: 15000.0).toString()) }
        var daily by remember { mutableStateOf((editingStaff?.dailyRate ?: 500.0).toString()) }
        var weeklyOff by remember { mutableStateOf(editingStaff?.weeklyOffDay ?: "Sunday") }
        var prevBal by remember { mutableStateOf((editingStaff?.previousBalance ?: 0.0).toString()) }
        var remarks by remember { mutableStateOf(editingStaff?.remarks ?: "") }

        AlertDialog(
            onDismissRequest = { showStaffDialog = false },
            containerColor = CardWhite,
            title = { Text(if (editingStaff == null) "Add Staff Member" else "Edit Staff Member", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(name, { name = it }, "Full Name *")
                    FormInputField(mobile, { mobile = it }, "Mobile Number", keyboardType = KeyboardType.Phone)
                    FormInputField(designation, { designation = it }, "Designation (Salesman, Loader, Driver...)")
                    Text("Salary Calculation Method", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                    Column {
                        listOf("DAYS_30" to "Fixed 30 Days", "ACTUAL_DAYS" to "Actual Days in Month", "WORKING_DAYS" to "Working Days (excl. Off)", "DAILY_WAGE" to "Daily Wage").forEach { (k, lbl) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = method == k, onClick = { method = k })
                                Text(lbl, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (method == "DAILY_WAGE") {
                        FormInputField(daily, { daily = it }, "Daily Wage Rate (₹)", keyboardType = KeyboardType.Decimal)
                    } else {
                        FormInputField(monthly, { monthly = it }, "Monthly Salary (₹)", keyboardType = KeyboardType.Decimal)
                    }
                    FormInputField(weeklyOff, { weeklyOff = it }, "Weekly Off Day (e.g. Sunday)")
                    FormInputField(prevBal, { prevBal = it }, "Previous Arrears / Balance (₹)", keyboardType = KeyboardType.Decimal)
                    FormInputField(remarks, { remarks = it }, "Remarks")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank()) return@Button
                        val model = (editingStaff ?: StaffMember()).copy(
                            fullName = name.trim(),
                            mobile = mobile.trim(),
                            designation = designation.trim(),
                            salaryMethod = method,
                            monthlySalary = monthly.toDoubleOrNull() ?: 0.0,
                            dailyRate = daily.toDoubleOrNull() ?: 0.0,
                            weeklyOffDay = weeklyOff.trim().ifBlank { "Sunday" },
                            previousBalance = prevBal.toDoubleOrNull() ?: 0.0,
                            remarks = remarks.trim()
                        )
                        onSaveStaff(model)
                        showStaffDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showStaffDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAdvanceDialog) {
        var selectedMember by remember { mutableStateOf(staff.firstOrNull()) }
        var amount by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf("Cash") }
        var purpose by remember { mutableStateOf("Salary Advance") }
        var reason by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAdvanceDialog = false },
            containerColor = CardWhite,
            title = { Text("Record Staff Advance", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (staff.isEmpty()) {
                        Text("Please add staff members first.", color = DangerRed)
                    } else {
                        Text("Select Staff", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                        staff.forEach { st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedMember?.id == st.id, onClick = { selectedMember = st })
                                Text(st.fullName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        FormInputField(amount, { amount = it }, "Advance Amount (₹) *", keyboardType = KeyboardType.Decimal)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Cash", "UPI", "Bank").forEach { m ->
                                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
                            }
                        }
                        FormInputField(purpose, { purpose = it }, "Purpose")
                        FormInputField(reason, { reason = it }, "Remarks / Reason")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val st = selectedMember ?: return@Button
                        val amt = amount.toDoubleOrNull() ?: return@Button
                        if (amt <= 0) return@Button
                        onSaveAdvance(
                            StaffAdvance(
                                staffId = st.id,
                                staffName = st.fullName,
                                amount = amt,
                                paymentMode = mode,
                                purpose = purpose.trim(),
                                reason = reason.trim()
                            )
                        )
                        showAdvanceDialog = false
                    },
                    enabled = staff.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save Advance") }
            },
            dismissButton = { TextButton(onClick = { showAdvanceDialog = false }) { Text("Cancel") } }
        )
    }

    if (secretActionNeedsCallbackWithCode != null) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { secretActionNeedsCallbackWithCode = null },
            containerColor = CardWhite,
            title = { Text(pendingSecretPromptTitle, color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Attendance edits & clearing require the owner-configured Secret Code.", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                    FormInputField(code, { code = it }, "Enter Secret Code", keyboardType = KeyboardType.NumberPassword, isPassword = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cb = secretActionNeedsCallbackWithCode
                        secretActionNeedsCallbackWithCode = null
                        cb?.invoke(code)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Verify & Apply") }
            },
            dismissButton = { TextButton(onClick = { secretActionNeedsCallbackWithCode = null }) { Text("Cancel") } }
        )
    }

    if (showAuditLogDialog) {
        val logs = activityLogs.filter { it.module.equals("Attendance", true) }
        AlertDialog(
            onDismissRequest = { showAuditLogDialog = false },
            containerColor = CardWhite,
            title = { Text("Attendance Audit Log", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (logs.isEmpty()) {
                        Text("No attendance audit changes logged yet.", color = SlateMuted)
                    } else {
                        logs.forEach { log ->
                            Text("• ${log.action}: ${log.details} (${log.actorName})", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(color = BorderLight)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAuditLogDialog = false }) { Text("Close") } }
        )
    }

    if (generatedPdf != null) {
        PdfActionDialog(file = generatedPdf!!, onDismiss = { generatedPdf = null })
    }
}
