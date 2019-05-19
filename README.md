# AirKeys
An Android application that uses the camera to recognize letters drawn in the air by your fingers.

# Installation

## Android Application
This project was built and created using Android Studio. You should be able to use Android Studio's Import-Project-from-VC feature to automatically download the program and any dependencies you need. After opening Android Studio, you'd do something roughly like this: `File -> New -> Project from Version Control -> GitHub`. After building the project, you should be able to use Android Studio's built-in ADB and run functionality to produce an APK to install on your phone.

## Character Recognition Server
While hand tracking is done on the phone, the actual character recognition was implemented in Python and is intended to run remotely. The app will send the points comprimising your character drawing to the server, which will then respond with its guess for the character. For the server to work correctly, you may want to replace the test IP address in `ImageClassifier` with your own server's machine address. Of course, you'll need to run the server code there.

```
$ mkdir images && virtualenv venv
$ source venv/bin/activate
(venv) $ pip install -r requirements.txt
(venv) $ python endpoint.py
...
Everything loaded! Waiting for connections...
```

## Optional Image Server
Each image drawn by the user on the app is saved on the server in the `images` folder in case you want to view it. You can optionally view it over HTTP by running `python image_server.py`, which supports:
- `/images/pic<n>.png` where `<n>` is the picture number
- `/last_image`, which redirects you to the last picture stored on the server.
- `/last`, which tells you the number of the last picture stored on the server.

## How to Use
With the server running, all you need to do after installing the app is launch it. You will see 9 green rectangles appear - you must first calibrate the application to your skin. For best results, you should align at least 1 rectangle on your fingernail so that it captures both your skin and fingernail colors. Also, do not leave gaps between your fingers when calibrating since the application will sample each rectangle's pixels to figure out what your skin colors are.

After you've aligned your hand, you simply need to tap the screen to complete calibration. The application will then mask away everything it sees that is not your hand or fingers and start running.

### Current Features
- Making a 1 Finger "pointing" gesture allows you draw letters and digits by sliding your finger, one character at a time.
- Making a 2 Finger "peace" gesture inserts a space character.
- Making a 5 Finger "high-five" gesture inserts a period character.

### Drawing Notes
- The app currently just looks for the topmost part of your hand and uses that as the drawing instrument. So if your knuckle is higher in the frame than your finger, it'll think your knuckle is your finger.
- Since the app is sampling every frame, your drawings must be contiguous or else we'll pick up stray dots as you move your finger around to make new strings. For some characters like "i" and "j" where it's impossible to be totally contiguous, we recommend making a dark "dot" for the tittle and then quickly jumping down to start your second stroke.
- To finish the character and indicate you're ready for it to be sent to the server for processing, simply keep your finger steady at one location.
- Some errors are presently unavoidable, such as confusion between "s" and "5" and capital / lowercase letters when the only difference is the size of the character.

## Credits/Inspiration
Most of the hand histogram masking algorithm was learned from <a href="http://www.benmeline.com/finger-tracking-with-opencv-and-python/">here</a>. Our contribution to this was applying an additional contour filter when displaying and adding a Gaussian blur to remove extra noise.
