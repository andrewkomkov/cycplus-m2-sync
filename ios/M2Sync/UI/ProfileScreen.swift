import SwiftUI

/// Профиль для калорий. Что есть в «Здоровье», берётся оттуда; недостающее вводится здесь.
struct ProfileScreen: View {
    @ObservedObject var sync: SyncController
    @Environment(\.dismiss) private var dismiss

    @State private var weight: Double?
    @State private var birthYear: Int?
    @State private var sex: Calories.Sex?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Weight") {
                        if let weight = sync.healthProfile.weightKg {
                            Text("\(weight.formatted(.number.precision(.fractionLength(1)))) kg")
                        } else {
                            Text("Not in Apple Health").foregroundStyle(.secondary)
                        }
                    }
                    LabeledContent("Year of Birth") {
                        if let year = sync.healthProfile.birthYear {
                            Text(verbatim: String(year))
                        } else {
                            Text("Not in Apple Health").foregroundStyle(.secondary)
                        }
                    }
                    LabeledContent("Sex") {
                        if let sex = sync.healthProfile.sex {
                            Text(sex.title)
                        } else {
                            Text("Not in Apple Health").foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("From Apple Health")
                } footer: {
                    Text("Read during sync. Values from Apple Health take priority.")
                }

                Section {
                    LabeledContent("Weight") {
                        TextField("kg", value: $weight, format: .number.precision(.fractionLength(0...1)))
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Year of Birth") {
                        TextField(String("1990"), value: $birthYear, format: .number.grouping(.never))
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                    }
                    Picker("Sex", selection: $sex) {
                        Text("Not Set").tag(Calories.Sex?.none)
                        ForEach(Calories.Sex.allCases, id: \.self) { sex in
                            Text(sex.title).tag(Calories.Sex?.some(sex))
                        }
                    }
                } header: {
                    Text("Manual")
                } footer: {
                    Text("The M2 records no calories. They are estimated from heart rate, or from speed where there is none, and saved to Apple Health as active energy. The heart-rate formula needs weight, year of birth and sex.")
                }
            }
            .navigationTitle("Profile for Calories")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { save() }
                        .disabled(!isValid)
                }
            }
            .onAppear {
                weight = sync.manualProfile.weightKg
                birthYear = sync.manualProfile.birthYear
                sex = sync.manualProfile.sex
            }
        }
    }

    private var isValid: Bool {
        let currentYear = Calendar.current.component(.year, from: Date())
        let yearIsValid = birthYear.map { (1900...currentYear).contains($0) } ?? true
        let weightIsValid = weight.map { (20...300).contains($0) } ?? true
        return yearIsValid && weightIsValid
    }

    private func save() {
        let profile = Calories.Profile(weightKg: weight, birthYear: birthYear, sex: sex)
        Task { await sync.updateManualProfile(profile) }
        dismiss()
    }
}

extension Calories.Sex {
    var title: LocalizedStringKey {
        switch self {
        case .male: "Male"
        case .female: "Female"
        }
    }
}
