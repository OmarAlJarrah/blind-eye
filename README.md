# Blind Eye
An application that uses external APIs to describe a scene captured by camera.

## How it works?
### Camera Scene Capture
A picture will be captured every 5 seconds automatically. The picture is then processed and sent to external APIs. The prompt ensures not to describe objects that were identified in the previous scene (scene has not changed). 

### Scene Description
The captured scene is sent to the [Gemini API](https://aistudio.google.com/), with a prompt to get a scene description.

### Audio Description
The scene description is sent to the [Text-to-Speech API](https://elevenlabs.io/app/speech-synthesis/text-to-speech) by ElevenLabs. An audio file is generated and played for the user.

