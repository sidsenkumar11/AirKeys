from flask import Flask, request, send_from_directory, url_for, redirect
import os

# set the project root directory as the static folder, you can set others.
app = Flask(__name__, static_url_path='')

@app.route('/images/<path>')
def send_image(path):
    return send_from_directory('images', path)

@app.route('/last')
def last_image_num():
    return str(len(os.listdir('./images')))

@app.route('/last_image')
def last_image():
    return redirect(url_for('send_image', path="pic" + str(len(os.listdir('./images'))) + '.png'))

if __name__ == "__main__":
    app.run(host='0.0.0.0', port=5001)

