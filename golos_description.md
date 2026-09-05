# GolosAI: whisper alternative:
## spech-to-text, when key pushed, then text pasted in users active text input field 

sequence:
push to talk key pressed
read user's voice input from audio device, which are system default by default or may be selected by user in preferences.
on a fly translate users speech to text
on a fly paste text into user's active text field 


implementation details:
Source code is written in Kotlin, project build by gradle
## Main project contains modules:
### application:
* Application should mostly orchestrate modules, not implement logical things, do not know about all the things happening, but provides and works with described interface with others
* UI for preferences: selecting mic input, audio processing strategy (whisper-large-turbo, or may be something algorithmical, it must be easily added from code)
* Application is main program which handles life cycle of voice backend and system utilities, voice processing states(IDLE, RECORDING, PROCESSING)


### voice backend:
*runs model
* receives audio flow(from application) and returns processed text (on a fly, or whisper.cpp solution: 500ms chunks -- handled by different functions or classes, decide by urself)
* Then the voice backend should handle voice formats, denoising, resampling, normalisation VAD etc, and provide simple API for others to proceed with primitive audio formats 

### system utilities:
* code for working with OS (targes OS is Linux with X11 DE, Windows 11, Mac OS)
* handling mic input
* handling keyboard input (holding key detection, even when application is folded/ hidden/ deactive)
* searching for active text input field.

### architecture of the solution:
When the application starts, it initializes the System Utilities module. The System Utilities module provides platform-specific functionality for microphone input, global keyboard handling, and writing text into the active input field. The Application coordinates these components and manages the dictation workflow. The Voice Backend is responsible for speech-processing logic, including audio format conversion, speech recognition, and translation models.


## allowed external dependencies: MIT or MIT based licensing
PortAudio - for audio capture 
whisper.cpp - audio backend artifact (use JNI bindings or something like it)
Swing - for UI

for keyboard handling and text insertion it may be more painful:
JNA
 ↓
Windows native keyboard API
macOS native event API
Linux/X11 or Wayland-specific implementation
TextInput
    │
    ├── Windows → native input
    ├── macOS   → native accessibility/input
    └── Linux   → platform-specific implementation
