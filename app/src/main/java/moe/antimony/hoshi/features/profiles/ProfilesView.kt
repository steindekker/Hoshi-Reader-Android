package moe.antimony.hoshi.features.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.profiles.HoshiProfile
import moe.antimony.hoshi.profiles.ProfileState

@Composable
internal fun ProfilesView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.profileState.collectAsStateWithLifecycle()
    ProfilesContent(
        state = state,
        onClose = onClose,
        onCreateProfile = viewModel::createProfile,
        onRenameProfile = viewModel::renameProfile,
        onDeleteProfile = viewModel::deleteProfile,
        onActivateGlobal = viewModel::activateGlobal,
        onSetPrimary = viewModel::setPrimaryProfile,
        modifier = modifier,
    )
}

@Composable
private fun ProfilesContent(
    state: ProfileState,
    onClose: () -> Unit,
    onCreateProfile: (String, String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onActivateGlobal: (String) -> Unit,
    onSetPrimary: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingProfile by remember { mutableStateOf<HoshiProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<HoshiProfile?>(null) }
    var creatingProfile by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        title = stringResource(R.string.profiles_title),
        onClose = onClose,
        modifier = modifier,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ProfileHeader(state)
            }
            items(state.profiles, key = { it.id }) { profile ->
                ProfileRow(
                    profile = profile,
                    state = state,
                    onEdit = { editingProfile = profile },
                    onDelete = { deletingProfile = profile },
                    onActivateGlobal = { onActivateGlobal(profile.id) },
                    onSetPrimary = { onSetPrimary(profile.dictionaryLanguageId, profile.id) },
                )
            }
            item {
                OutlinedButton(
                    onClick = { creatingProfile = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.profiles_create))
                }
            }
        }
    }

    if (creatingProfile) {
        ProfileEditDialog(
            title = stringResource(R.string.profiles_create),
            initialName = "",
            initialLanguageId = ContentLanguageProfile.English.dictionaryLanguageId,
            allowLanguageChange = true,
            onDismiss = { creatingProfile = false },
            onConfirm = { name, languageId ->
                onCreateProfile(name, languageId)
                creatingProfile = false
            },
        )
    }

    editingProfile?.let { profile ->
        ProfileEditDialog(
            title = profile.name,
            initialName = profile.name,
            initialLanguageId = profile.dictionaryLanguageId,
            allowLanguageChange = false,
            onDismiss = { editingProfile = null },
            onConfirm = { name, _ ->
                onRenameProfile(profile.id, name)
                editingProfile = null
            },
        )
    }

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.profiles_delete_title_format, profile.name)) },
            text = {
                Text(
                    if (profile.isDefault) {
                        stringResource(R.string.profiles_default_cannot_delete)
                    } else {
                        profile.name
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !profile.isDefault,
                    onClick = {
                        onDeleteProfile(profile.id)
                        deletingProfile = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deletingProfile = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileHeader(state: ProfileState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.profiles_active_global),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = state.globalActiveProfile.name,
            style = MaterialTheme.typography.titleMedium,
        )
        state.loadedProfileId?.let {
            Text(
                text = stringResource(R.string.profiles_loaded_reader),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = state.effectiveProfile.name,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: HoshiProfile,
    state: ProfileState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onActivateGlobal: () -> Unit,
    onSetPrimary: () -> Unit,
) {
    val language = ContentLanguageProfile.fromDictionaryLanguageId(profile.dictionaryLanguageId)
        ?: ContentLanguageProfile.Default
    val isGlobal = state.globalActiveProfileId == profile.id
    val isPrimary = state.primaryProfileIdsByLanguage[profile.dictionaryLanguageId] == profile.id

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(language.displayNameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_rename))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onActivateGlobal,
                    label = { Text(stringResource(R.string.profiles_set_global_active)) },
                    leadingIcon = {
                        Icon(
                            if (isGlobal) Icons.Rounded.Check else Icons.Rounded.RadioButtonChecked,
                            contentDescription = null,
                        )
                    },
                )
                AssistChip(
                    onClick = onSetPrimary,
                    label = { Text(stringResource(R.string.profiles_set_primary)) },
                    leadingIcon = {
                        Icon(
                            if (isPrimary) Icons.Rounded.Check else Icons.Rounded.Language,
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditDialog(
    title: String,
    initialName: String,
    initialLanguageId: String,
    allowLanguageChange: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var languageId by remember(initialLanguageId) { mutableStateOf(initialLanguageId) }
    var languageExpanded by remember { mutableStateOf(false) }
    val selectedLanguage = ContentLanguageProfile.fromDictionaryLanguageId(languageId) ?: ContentLanguageProfile.Default

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_new_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { if (allowLanguageChange) languageExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(selectedLanguage.displayNameRes),
                        onValueChange = {},
                        readOnly = true,
                        enabled = allowLanguageChange,
                        label = { Text(stringResource(R.string.profiles_language)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        ContentLanguageProfile.Supported.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(stringResource(language.displayNameRes)) },
                                onClick = {
                                    languageId = language.dictionaryLanguageId
                                    languageExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, languageId) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
